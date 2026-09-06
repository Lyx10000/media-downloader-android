package com.local.douyindownloader

import androidx.work.ExistingWorkPolicy
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class CreatorBatchPrepareOutcome(
    val success: Boolean,
    val taskId: String,
    val message: String = "",
)

@Singleton
internal class CreatorBatchTaskPreparer @Inject constructor(
    private val tasks: DownloadTaskRepository,
    private val batches: DownloadBatchDao,
    private val scheduler: DownloadScheduler,
) {
    suspend fun prepare(
        batchId: String,
        profile: CreatorProfile,
        indexedWork: CreatorWork,
        sourceUrl: String,
        result: ParseResult,
        settings: BatchDownloadSettings,
        appSettings: AppSettings,
    ): CreatorBatchPrepareOutcome {
        val taskId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val storageMode = if (appSettings.customTreeUri.isNullOrBlank()) {
            StorageMode.DEFAULT
        } else StorageMode.SAF
        val spec = TaskSpec(
            taskId = taskId,
            createdAt = createdAt,
            result = result,
            variantIndex = chooseBatchVariant(result.variants, settings.quality, settings.preferH264),
            mode = settings.mode,
            sourceText = sourceUrl,
            storageMode = storageMode,
            storageRoot = appSettings.customTreeUri.orEmpty(),
            taskFolder = creatorWorkFolder(profile, indexedWork, createdAt),
            authorKey = profile.key,
            batchId = batchId,
            creatorChild = true,
        )
        return try {
            tasks.insert(spec)
            batches.updateWork(
                batchId,
                indexedWork.key,
                CreatorBatchWorkStatus.PREPARED,
                taskId,
                "",
            )
            CreatorBatchPrepareOutcome(true, taskId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val safeMessage = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            runCatching { tasks.update(taskId, TaskStatus.FAILED, "创建下载任务失败", 0, safeMessage) }
            batches.updateWork(
                batchId,
                indexedWork.key,
                CreatorBatchWorkStatus.FAILED,
                taskId,
                safeMessage,
            )
            CreatorBatchPrepareOutcome(false, taskId, safeMessage)
        }
    }

    suspend fun submitPrepared(batchId: String) {
        batches.listWorks(batchId)
            .filter { it.status == CreatorBatchWorkStatus.PREPARED && it.taskId.isNotBlank() }
            .forEach { entry ->
                try {
                    scheduler.enqueue(entry.taskId, ExistingWorkPolicy.KEEP)
                    batches.updateWork(
                        batchId,
                        entry.workKey,
                        CreatorBatchWorkStatus.SCHEDULED,
                        entry.taskId,
                        "",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val safeMessage = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                    runCatching {
                        tasks.update(entry.taskId, TaskStatus.FAILED, "启动下载失败", 0, safeMessage)
                    }
                    batches.updateWork(
                        batchId,
                        entry.workKey,
                        CreatorBatchWorkStatus.FAILED,
                        entry.taskId,
                        safeMessage,
                    )
                }
            }
    }
}
