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

class DownloadWorker internal constructor(
    appContext: Context,
    params: WorkerParameters,
    private val repository: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val executor: DownloadExecutor,
    private val settingsRepository: SettingsRepository,
    private val managedFileGateway: ManagedFileGateway,
    private val taskFolderPruner: TaskFolderPruner,
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
        var originalSpec: TaskSpec? = null
        var executionSpec: TaskSpec? = null
        var replacementCommitted = false
        var stagedOutputs = emptyList<TaskOutput>()
        try {
            safeEvent(taskId, "STARTUP", "WORKER_ENTERED", workerDetails())
            var storedSpec = repository.getSpec(taskId)
                ?: error("任务规格不存在或已经损坏")
            storedSpec = discardInterruptedReplacement(taskId, storedSpec)
            originalSpec = storedSpec
            safeEvent(taskId, "STARTUP", "SPEC_LOADED", JSONObject().apply {
                put("content_id", storedSpec.result.contentId)
                put("platform", storedSpec.result.platform.wireValue)
                put("kind", storedSpec.result.kind.wireValue)
            })
            val requestedSpec = storedSpec.executionSpec()
            val spec = if (requestedSpec.storageMode == StorageMode.LEGACY) {
                val currentTree = settingsRepository.current().customTreeUri
                requestedSpec.copy(
                    storageMode = if (currentTree.isNullOrBlank()) StorageMode.DEFAULT else StorageMode.SAF,
                    storageRoot = currentTree.orEmpty(),
                )
            } else {
                requestedSpec
            }
            executionSpec = spec
            storedSpec.pendingRedownload?.let { pending ->
                safeEvent(taskId, "REDOWNLOAD", "TRANSACTION_STARTED", JSONObject().apply {
                    put("old_folder", storedSpec.taskFolder)
                    put("new_folder", spec.taskFolder)
                    put("previous_outputs", pending.previousOutputs.size)
                })
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
            val execution = executor.execute(
                taskId = taskId,
                spec = spec,
                folder = activeTaskFolder,
                persistIntermediateOutputs = storedSpec.pendingRedownload == null,
                onPublishedOutputs = { outputs ->
                    stagedOutputs = outputs
                    val base = originalSpec
                    val pending = base?.pendingRedownload
                    if (pending != null) {
                        val updated = base.copy(
                            pendingRedownload = pending.copy(stagedOutputs = outputs),
                        )
                        repository.replaceSpec(taskId, updated)
                        originalSpec = updated
                    }
                },
            ) { stage, progress, persist ->
                if (persist) repository.update(taskId, TaskStatus.RUNNING, stage, progress)
                setForeground(createForeground(taskId, stage, progress))
            }
            val completionStage = if (execution.warningCount > 0) {
                "已完成（${execution.warningCount} 项未下载）"
            } else {
                "已完成"
            }
            val pending = storedSpec.pendingRedownload
            if (pending == null) {
                repository.complete(taskId, execution.outputs, completionStage)
            } else {
                repository.replaceSpec(taskId, storedSpec.committedRedownloadSpec())
                repository.complete(taskId, execution.outputs, completionStage)
                replacementCommitted = true
                val cleanup = cleanupArtifacts(
                    taskId = taskId,
                    outputs = pending.previousOutputs,
                    spec = storedSpec.copy(pendingRedownload = null),
                    eventPrefix = "OLD",
                )
                if (!cleanup.success) {
                    repository.update(
                        taskId,
                        TaskStatus.COMPLETE,
                        "已完成（旧文件清理失败）",
                        100,
                        cleanup.message,
                    )
                }
                safeEvent(taskId, "REDOWNLOAD", "TRANSACTION_COMMITTED", JSONObject().apply {
                    put("new_outputs", execution.outputs.size)
                    put("old_cleanup_success", cleanup.success)
                    put("old_folder_retained", cleanup.folderRetained)
                    put("message", cleanup.message)
                })
            }
            safeEvent(
                taskId,
                "COMPLETE",
                "TASK_COMPLETE",
                JSONObject().apply {
                    put("files", execution.outputs.size)
                    put("warnings", execution.warningCount)
                },
            )
            setForeground(createForeground(taskId, completionStage, 100))
            activeTaskFolder.deleteRecursively()
            Result.success()
        } catch (cancelled: CancellationException) {
            if (replacementCommitted) {
                safeEvent(taskId, "REDOWNLOAD", "POST_COMMIT_CANCEL_IGNORED")
                taskFolder?.deleteRecursively()
                return@withContext Result.success()
            }
            val pending = originalSpec?.pendingRedownload
            if (pending != null && executionSpec != null) {
                rollbackReplacement(
                    taskId,
                    originalSpec!!,
                    executionSpec!!,
                    pending,
                    stagedOutputs,
                    "已取消",
                )
            } else {
                runCatching { repository.update(taskId, TaskStatus.CANCELLED, "已取消", 0) }
            }
            safeEvent(taskId, "DOWNLOAD", "TASK_CANCELLED")
            taskFolder?.deleteRecursively()
            throw cancelled
        } catch (error: Throwable) {
            val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            if (replacementCommitted) {
                safeEvent(taskId, "REDOWNLOAD", "POST_COMMIT_WARNING", JSONObject().apply {
                    put("message", message)
                })
                taskFolder?.deleteRecursively()
                return@withContext Result.success()
            }
            val pending = originalSpec?.pendingRedownload
            if (pending != null && executionSpec != null) {
                rollbackReplacement(
                    taskId,
                    originalSpec!!,
                    executionSpec!!,
                    pending,
                    stagedOutputs,
                    message,
                )
            } else {
                runCatching { repository.update(taskId, TaskStatus.FAILED, "失败", 0, message) }
            }
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

    private suspend fun discardInterruptedReplacement(
        taskId: String,
        originalSpec: TaskSpec,
    ): TaskSpec {
        val pending = originalSpec.pendingRedownload ?: return originalSpec
        if (pending.stagedOutputs.isEmpty()) return originalSpec
        cleanupArtifacts(taskId, pending.stagedOutputs, originalSpec.executionSpec(), "INTERRUPTED")
        val cleared = originalSpec.copy(
            pendingRedownload = pending.copy(stagedOutputs = emptyList()),
        )
        repository.replaceSpec(taskId, cleared)
        return cleared
    }

    private suspend fun rollbackReplacement(
        taskId: String,
        originalSpec: TaskSpec,
        spec: TaskSpec,
        pending: PendingRedownload,
        stagedOutputs: List<TaskOutput>,
        reason: String,
    ) {
        val generatedOutputs = (pending.stagedOutputs + stagedOutputs).distinctBy(TaskOutput::uri)
        val cleanup = cleanupArtifacts(taskId, generatedOutputs, spec, "ROLLBACK")
        runCatching { repository.replaceSpec(taskId, originalSpec.copy(pendingRedownload = null)) }
        val safeReason = Redactor.sanitize(reason)
        runCatching {
            repository.update(
                taskId,
                if (reason == "已取消") TaskStatus.CANCELLED else TaskStatus.FAILED,
                if (reason == "已取消") "已取消，原文件已保留" else "重新下载失败，原文件已保留",
                0,
                safeReason.takeUnless { reason == "已取消" }.orEmpty(),
            )
        }
        safeEvent(taskId, "REDOWNLOAD", "TRANSACTION_ROLLED_BACK", JSONObject().apply {
            put("generated_outputs", generatedOutputs.size)
            put("cleanup_success", cleanup.success)
            put("folder_retained", cleanup.folderRetained)
            put("reason", safeReason)
        })
    }

    private data class ArtifactCleanup(
        val success: Boolean,
        val folderRetained: Boolean,
        val message: String,
    )

    private fun cleanupArtifacts(
        taskId: String,
        outputs: List<TaskOutput>,
        spec: TaskSpec,
        eventPrefix: String,
    ): ArtifactCleanup {
        val failures = outputs.filterNot { output ->
            val deleted = runCatching { managedFileGateway.delete(output) }.getOrDefault(false)
            safeEvent(taskId, "REDOWNLOAD", "${eventPrefix}_OUTPUT_DELETE_RESULT", JSONObject().apply {
                put("name", output.displayName)
                put("deleted_or_missing", deleted)
            })
            deleted
        }.map { it.displayName.ifBlank { "未命名文件" } }.toMutableList()
        val folder = taskFolderPruner.pruneIfEmpty(spec)
        if (!folder.success) failures += folder.message
        return ArtifactCleanup(
            success = failures.isEmpty(),
            folderRetained = folder.retained,
            message = failures.joinToString("；"),
        )
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
        val complete = stage == "已完成" || stage.startsWith("已完成（")
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("聚合下载器")
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
