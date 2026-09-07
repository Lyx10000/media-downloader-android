package com.local.multiplatformdownloader.app

import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.feature.download.DownloadExecutor
import com.local.multiplatformdownloader.feature.download.DownloadWorker
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.storage.TaskFolderPruner
import com.local.multiplatformdownloader.feature.creator.CreatorBatchCoordinator
import com.local.multiplatformdownloader.feature.creator.CreatorBatchWorker
import com.local.multiplatformdownloader.core.settings.SettingsRepository
import com.local.multiplatformdownloader.feature.tasks.ManagedFileGateway
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveCoordinator
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveWorker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.local.multiplatformdownloader.core.compat.matchesPersistedWorkerClass
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
        if (matchesPersistedWorkerClass(
                workerClassName,
                CreatorBatchWorker::class.java.name,
                CreatorBatchWorker::class.java.simpleName,
            )
        ) {
            return CreatorBatchWorker(
                appContext,
                workerParameters,
                creatorBatchCoordinator.get(),
                logger,
            )
        }
        if (matchesPersistedWorkerClass(
                workerClassName,
                ZhihuQuestionArchiveWorker::class.java.name,
                ZhihuQuestionArchiveWorker::class.java.simpleName,
            )
        ) {
            return ZhihuQuestionArchiveWorker(
                appContext,
                workerParameters,
                zhihuQuestionArchiveCoordinator.get(),
                logger,
            )
        }
        if (!matchesPersistedWorkerClass(
                workerClassName,
                DownloadWorker::class.java.name,
                DownloadWorker::class.java.simpleName,
            )
        ) return null
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
