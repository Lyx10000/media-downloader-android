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
    private val redownload: TaskRedownloadCoordinator,
) {
    suspend fun prepare(
        batchId: String,
        profile: CreatorProfile,
        indexedWork: CreatorWork,
        sourceUrl: String,
        result: ParseResult,
        settings: BatchDownloadSettings,
        appSettings: AppSettings,
        cookieHeader: String = "",
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
        val created = mutableListOf<String>()
        return try {
            val parts = result.bilibiliParts.takeIf { result.platform == SourcePlatform.BILIBILI && it.size > 1 }
                ?.let { if (settings.bilibiliAllParts) it else it.filter { p -> p.page == 1 } }.orEmpty()
            val specs = if (parts.isEmpty()) listOf(spec) else parts.mapIndexed { index, part ->
                val child = bilibiliPartResult(result, part)
                val childId = if (index == 0) taskId else UUID.randomUUID().toString()
                spec.copy(taskId = childId, result = child, sourceText = child.canonicalUrl,
                    bilibiliPending = child.variants.all { it.urls.isEmpty() },
                    bilibiliBatchQuality = settings.quality.wireValue,
                    taskFolder = "${spec.taskFolder}/P${part.page}_${part.cid}_${childId.take(8)}")
            }
            val existing = if (result.platform == SourcePlatform.BILIBILI) indexedWork.relatedTasks
                .sortedByDescending { it.createdAt }.distinctBy { it.contentId }.associateBy { it.contentId } else emptyMap()
            var reusedId = ""
            specs.forEach { child ->
                val previous = existing[child.result.contentId]
                if (previous == null) {
                    tasks.insert(child)
                    created += child.taskId
                } else {
                    reusedId = previous.id
                    if (previous.status !in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.DELETING)) {
                        val retry = redownload.retry(previous, cookieHeader, appSettings.customTreeUri, settings)
                        check(retry.success) { retry.message }
                    }
                }
            }
            val primaryId = created.firstOrNull() ?: reusedId
            batches.updateWork(
                batchId,
                indexedWork.key,
                if (created.isEmpty()) CreatorBatchWorkStatus.SCHEDULED else CreatorBatchWorkStatus.PREPARED,
                primaryId,
                "",
            )
            CreatorBatchPrepareOutcome(true, primaryId)
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                created.forEach { tasks.update(it, TaskStatus.FAILED, "准备已中断，可手动重试", 0) }
            }
            throw cancelled
        } catch (error: Throwable) {
            val safeMessage = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            created.forEach { id -> runCatching { tasks.update(id, TaskStatus.FAILED, "创建下载任务失败", 0, safeMessage) } }
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
        val partTasks = tasks.listAll().filter { it.batchId == batchId && it.platform == SourcePlatform.BILIBILI }
        batches.listWorks(batchId)
            .filter { it.status == CreatorBatchWorkStatus.PREPARED && it.taskId.isNotBlank() }
            .forEach { entry ->
                try {
                    val grouped = partTasks.filter {
                        creatorWorkKey(it.platform, bilibiliWorkId(it.platform, it.contentId)) == entry.workKey
                    }
                    val ids = grouped.map { it.id }.ifEmpty { listOf(entry.taskId) }
                    var failure = ""
                    ids.forEach { id ->
                        try { scheduler.enqueue(id, ExistingWorkPolicy.KEEP) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Throwable) {
                            failure = Redactor.sanitize(error.message ?: "启动下载失败")
                            tasks.update(id, TaskStatus.FAILED, "启动下载失败", 0, failure)
                        }
                    }
                    batches.updateWork(
                        batchId,
                        entry.workKey,
                        if (failure.isEmpty()) CreatorBatchWorkStatus.SCHEDULED else CreatorBatchWorkStatus.FAILED,
                        entry.taskId,
                        failure,
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
