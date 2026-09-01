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
    private lateinit var taskId: String

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val storedSpec = repository.getSpec(taskId) ?: return@withContext Result.failure()
        val spec = if (storedSpec.storageMode == StorageMode.LEGACY) {
            val currentTree = settingsRepository.current().customTreeUri
            storedSpec.copy(
                storageMode = if (currentTree.isNullOrBlank()) StorageMode.DEFAULT else StorageMode.SAF,
                storageRoot = currentTree.orEmpty(),
            )
        } else {
            storedSpec
        }
        setForeground(createForeground("准备下载", 0))
        val taskFolder = File(applicationContext.cacheDir, "downloadTasks/$taskId").apply { mkdirs() }
        try {
            update("准备下载", 0)
            PublicStorage.ensureDestination(applicationContext, spec)
            logger.event(taskId, "STORAGE", "DESTINATION_READY", JSONObject().apply {
                put("mode", spec.storageMode.wireValue)
                put("task_folder", spec.taskFolder)
            })
            logger.event(taskId, "DOWNLOAD", "TASK_STARTED", JSONObject().apply {
                put("aweme_id", spec.result.awemeId)
                put("kind", spec.result.kind.wireValue)
                put("mode", spec.mode.wireValue)
            })
            val outputs = executor.execute(taskId, spec, taskFolder) { stage, progress, persist ->
                if (persist) repository.update(taskId, TaskStatus.RUNNING, stage, progress)
                setForeground(createForeground(stage, progress))
            }
            repository.complete(taskId, outputs)
            logger.event(taskId, "COMPLETE", "TASK_COMPLETE", JSONObject().put("files", outputs.size))
            setForeground(createForeground("下载完成", 100))
            taskFolder.deleteRecursively()
            Result.success()
        } catch (cancelled: CancellationException) {
            repository.update(taskId, TaskStatus.CANCELLED, "已取消", 0)
            logger.event(taskId, "DOWNLOAD", "TASK_CANCELLED")
            taskFolder.deleteRecursively()
            throw cancelled
        } catch (error: Throwable) {
            val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            repository.update(taskId, TaskStatus.FAILED, "失败", 0, message)
            logger.event(taskId, "DOWNLOAD", "TASK_FAILED", JSONObject().apply {
                put("type", error.javaClass.name)
                put("message", message)
                put("cached_files", taskFolder.listFiles()?.map(File::getName).orEmpty())
            })
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForeground("等待下载", 0)

    private suspend fun update(stage: String, progress: Int) {
        repository.update(taskId, TaskStatus.RUNNING, stage, progress)
        setForeground(createForeground(stage, progress))
    }

    private fun createForeground(stage: String, progress: Int): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("抖音下载器")
            .setContentText(stage)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress, progress <= 0)
            .build()
        return ForegroundInfo(
            taskId.hashCode().and(0x7fffffff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val CHANNEL_ID = "douyin_downloads"
    }
}
