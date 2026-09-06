package com.local.douyindownloader

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class AppWorkerFactory @Inject internal constructor(
    private val repository: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val executor: DownloadExecutor,
    private val settingsRepository: SettingsRepository,
    private val managedFileGateway: ManagedFileGateway,
    private val taskFolderPruner: TaskFolderPruner,
    // Keep this lazy: eager creation asks for WorkManager while the Application's
    // WorkerFactory is still being injected, which causes an initialization cycle.
    private val creatorBatchCoordinator: Provider<CreatorBatchCoordinator>,
    private val zhihuQuestionArchiveCoordinator: Provider<ZhihuQuestionArchiveCoordinator>,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName == CreatorBatchWorker::class.java.name) {
            return CreatorBatchWorker(
                appContext,
                workerParameters,
                creatorBatchCoordinator.get(),
                logger,
            )
        }
        if (workerClassName == ZhihuQuestionArchiveWorker::class.java.name) {
            return ZhihuQuestionArchiveWorker(
                appContext,
                workerParameters,
                zhihuQuestionArchiveCoordinator.get(),
                logger,
            )
        }
        if (workerClassName != DownloadWorker::class.java.name) return null
        val taskId = workerParameters.inputData.getString(DownloadWorker.KEY_TASK_ID)
            .orEmpty()
            .ifBlank { "work-${workerParameters.id}" }
        runCatching {
            logger.event(taskId, "STARTUP", "WORKER_CREATED", JSONObject().apply {
                put("work_id", workerParameters.id.toString())
                put("run_attempt", workerParameters.runAttemptCount)
            })
        }
        return DownloadWorker(
            appContext,
            workerParameters,
            repository,
            logger,
            executor,
            settingsRepository,
            managedFileGateway,
            taskFolderPruner,
        )
    }
}
