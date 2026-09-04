package com.local.douyindownloader

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val executor: DownloadExecutor,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK_ID)
        if (taskId.isNullOrBlank()) {
            safeEvent(
                "work-$id",
                "STARTUP",
                "WORKER_INPUT_MISSING",
                JSONObject().put("work_id", id.toString()),
            )
            return@withContext Result.failure()
        }
        var taskFolder: File? = null
        try {
            safeEvent(taskId, "STARTUP", "WORKER_ENTERED", workerDetails())
            val storedSpec = repository.getSpec(taskId)
                ?: error("任务规格不存在或已经损坏")
            safeEvent(taskId, "STARTUP", "SPEC_LOADED", JSONObject().apply {
                put("content_id", storedSpec.result.contentId)
                put("platform", storedSpec.result.platform.wireValue)
                put("kind", storedSpec.result.kind.wireValue)
            })
            val spec = if (storedSpec.storageMode == StorageMode.LEGACY) {
                val currentTree = settingsRepository.current().customTreeUri
                storedSpec.copy(
                    storageMode = if (currentTree.isNullOrBlank()) StorageMode.DEFAULT else StorageMode.SAF,
                    storageRoot = currentTree.orEmpty(),
                )
            } else {
                storedSpec
            }
            setForeground(createForeground(taskId, "准备下载", 0))
            safeEvent(taskId, "STARTUP", "FOREGROUND_READY")
            val activeTaskFolder = File(applicationContext.cacheDir, "downloadTasks/$taskId").apply {
                if (!mkdirs() && !isDirectory) error("无法创建任务缓存目录")
            }
            taskFolder = activeTaskFolder
            update(taskId, "准备下载", 0)
            PublicStorage.ensureDestination(applicationContext, spec)
            safeEvent(taskId, "STORAGE", "DESTINATION_READY", JSONObject().apply {
                put("mode", spec.storageMode.wireValue)
                put("task_folder", spec.taskFolder)
            })
            safeEvent(taskId, "DOWNLOAD", "TASK_STARTED", JSONObject().apply {
                put("content_id", spec.result.contentId)
                put("platform", spec.result.platform.wireValue)
                put("kind", spec.result.kind.wireValue)
                put("mode", spec.mode.wireValue)
            })
            val outputs = executor.execute(taskId, spec, activeTaskFolder) { stage, progress, persist ->
                if (persist) repository.update(taskId, TaskStatus.RUNNING, stage, progress)
                setForeground(createForeground(taskId, stage, progress))
            }
            repository.complete(taskId, outputs)
            safeEvent(
                taskId,
                "COMPLETE",
                "TASK_COMPLETE",
                JSONObject().put("files", outputs.size),
            )
            setForeground(createForeground(taskId, "下载完成", 100))
            activeTaskFolder.deleteRecursively()
            Result.success()
        } catch (cancelled: CancellationException) {
            runCatching { repository.update(taskId, TaskStatus.CANCELLED, "已取消", 0) }
            safeEvent(taskId, "DOWNLOAD", "TASK_CANCELLED")
            taskFolder?.deleteRecursively()
            throw cancelled
        } catch (error: Throwable) {
            val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            runCatching { repository.update(taskId, TaskStatus.FAILED, "失败", 0, message) }
            safeEvent(taskId, "DOWNLOAD", "TASK_FAILED", JSONObject().apply {
                put("type", error.javaClass.name)
                put("message", message)
                put("cached_files", taskFolder?.listFiles()?.map(File::getName).orEmpty())
                put("work_id", id.toString())
                put("run_attempt", runAttemptCount)
            })
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty().ifBlank { "work-$id" }
        return createForeground(taskId, "等待下载", 0)
    }

    private suspend fun update(taskId: String, stage: String, progress: Int) {
        repository.update(taskId, TaskStatus.RUNNING, stage, progress)
        setForeground(createForeground(taskId, stage, progress))
    }

    private fun createForeground(taskId: String, stage: String, progress: Int): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val showProgress = shouldShowDownloadProgress(stage)
        val complete = stage == "下载完成"
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("原画质下载器")
            .setContentText(stage)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(!complete)
        if (showProgress) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, false)
        }
        val notification: Notification = builder.build()
        return ForegroundInfo(
            taskId.hashCode().and(0x7fffffff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun workerDetails(): JSONObject = JSONObject().apply {
        put("work_id", id.toString())
        put("run_attempt", runAttemptCount)
        put("stopped", isStopped)
    }

    private fun safeEvent(
        taskId: String,
        stage: String,
        name: String,
        details: JSONObject = JSONObject(),
    ) {
        runCatching { logger.event(taskId, stage, name, details) }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val CHANNEL_ID = "douyin_downloads"
    }
}
