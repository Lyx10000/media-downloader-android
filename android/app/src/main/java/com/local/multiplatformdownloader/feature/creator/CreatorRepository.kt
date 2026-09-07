package com.local.multiplatformdownloader.feature.creator

import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.feature.zhihuarchive.toEntity
import com.local.multiplatformdownloader.platform.bilibili.bilibiliWorkId
import com.local.multiplatformdownloader.platform.bilibili.representativeCreatorTask

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class CreatorBatchSummary(
    val batchId: String,
    val selected: Int,
    val processed: Int,
    val progressFraction: Float,
    val activeTaskIds: Set<String>,
    val visible: Boolean,
    val queued: Int,
    val paused: Int,
    val foregroundRequired: Int,
    val complete: Int,
    val failed: Int,
)

@Singleton
class CreatorLibraryRepository @Inject internal constructor(
    private val creatorDao: CreatorDao,
    private val workDao: CreatorWorkDao,
    private val pageDao: CreatorPageDao,
    private val batchDao: DownloadBatchDao,
    private val taskRepository: DownloadTaskRepository,
) {
    fun observeCreators(): Flow<List<CreatorProfile>> = creatorDao.observeAll().map { entities ->
        entities.map(CreatorEntity::toProfile)
    }

    fun observeBatchSummaries(): Flow<Map<String, CreatorBatchSummary>> = combine(
        batchDao.observeBatches(),
        batchDao.observeWorks(),
        taskRepository.observeAll(),
    ) { batches, works, tasks ->
        val latest = actionableCreatorBatches(batches, works)
        latest.associate { batch ->
            val entries = works.filter { it.batchId == batch.batchId }
            val batchTasks = tasks.filter { it.batchId == batch.batchId }
            val batchTasksById = batchTasks.associateBy(TaskRecord::id)
            val batchBiliTasksByWork = batchTasks.filter { it.platform == SourcePlatform.BILIBILI }
                .groupBy { creatorWorkKey(it.platform, bilibiliWorkId(it.platform, it.contentId)) }
            fun currentEntryTasks(entry: BatchWorkEntity): List<TaskRecord> =
                batchBiliTasksByWork[entry.workKey] ?: listOfNotNull(batchTasksById[entry.taskId])
            val entryRecords = entries.associateWith(::currentEntryTasks)
            val terminalStatuses = setOf(TaskStatus.COMPLETE, TaskStatus.FAILED, TaskStatus.CANCELLED)
            val processed = entries.count { entry ->
                entry.status == CreatorBatchWorkStatus.FAILED ||
                    entryRecords.getValue(entry).let { records ->
                        records.isNotEmpty() && records.all { it.status in terminalStatuses }
                    }
            }
            val activeTaskIds = entryRecords.values.flatten().filter {
                it.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)
            }.mapTo(linkedSetOf(), TaskRecord::id)
            val pendingPreparation = entries.any {
                it.status in setOf(
                    CreatorBatchWorkStatus.QUEUED,
                    CreatorBatchWorkStatus.PARSING,
                    CreatorBatchWorkStatus.PARSING_HTTP,
                    CreatorBatchWorkStatus.WEB_REQUIRED,
                    CreatorBatchWorkStatus.PREPARED,
                    CreatorBatchWorkStatus.PAUSED,
                )
            }
            val progressUnits = entries.sumOf { entry ->
                val records = entryRecords.getValue(entry)
                when {
                    entry.status == CreatorBatchWorkStatus.FAILED -> 1.0
                    records.isEmpty() -> 0.0
                    else -> records.sumOf { task ->
                        when (task.status) {
                            TaskStatus.COMPLETE, TaskStatus.FAILED, TaskStatus.CANCELLED -> 1.0
                            TaskStatus.RUNNING -> task.progress.coerceIn(0, 100) / 100.0
                            else -> 0.0
                        }
                    } / records.size
                }
            }
            batch.creatorKey to CreatorBatchSummary(
                batchId = batch.batchId,
                selected = batch.selectedCount,
                processed = processed,
                progressFraction = if (batch.selectedCount > 0) {
                    (progressUnits / batch.selectedCount).toFloat().coerceIn(0f, 1f)
                } else 0f,
                activeTaskIds = activeTaskIds,
                visible = pendingPreparation || activeTaskIds.isNotEmpty(),
                queued = entries.count { entry ->
                    entry.status in setOf(
                        CreatorBatchWorkStatus.QUEUED,
                        CreatorBatchWorkStatus.PARSING,
                        CreatorBatchWorkStatus.PARSING_HTTP,
                        CreatorBatchWorkStatus.WEB_REQUIRED,
                        CreatorBatchWorkStatus.PREPARED,
                    ) || entry.status == CreatorBatchWorkStatus.SCHEDULED &&
                    currentEntryTasks(entry).any { it.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING) }
                },
                paused = entries.count {
                    it.status == CreatorBatchWorkStatus.PAUSED ||
                        batch.status == CreatorBatchStatus.PAUSED &&
                        it.status == CreatorBatchWorkStatus.WEB_REQUIRED
                },
                foregroundRequired = if (batch.status == CreatorBatchStatus.WAITING_FOREGROUND) {
                    entries.count { it.status == CreatorBatchWorkStatus.WEB_REQUIRED }
                } else 0,
                complete = entries.count { entry ->
                    currentEntryTasks(entry).let { records -> records.isNotEmpty() && records.all { it.status == TaskStatus.COMPLETE } }
                },
                failed = entries.count { entry ->
                    entry.status == "FAILED" ||
                        currentEntryTasks(entry).any { it.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED) }
                },
            )
        }
    }

    fun observeBatchPreparations(): Flow<Map<String, CreatorBatchPreparation>> = combine(
        batchDao.observeBatches(),
        batchDao.observeWorks(),
    ) { batches, works ->
        actionableCreatorBatches(batches, works).associate { batch ->
            val entries = works.filter { it.batchId == batch.batchId }
            val pending = entries.firstOrNull {
                it.status == CreatorBatchWorkStatus.WEB_REQUIRED ||
                    it.status == CreatorBatchWorkStatus.PARSING
            }
            val processed = entries.count {
                it.status in setOf(
                    CreatorBatchWorkStatus.PREPARED,
                    CreatorBatchWorkStatus.SCHEDULED,
                ) ||
                    it.status == CreatorBatchWorkStatus.FAILED
            }
            batch.creatorKey to CreatorBatchPreparation(
                batchId = batch.batchId,
                creatorKey = batch.creatorKey,
                status = batch.status,
                total = batch.selectedCount,
                processed = processed,
                pendingWorkKey = pending?.workKey.orEmpty(),
                pendingUrl = pending?.sourceUrl.orEmpty(),
                pendingStatus = pending?.status.orEmpty(),
                attemptCount = pending?.attemptCount ?: 0,
                lastError = pending?.error.orEmpty(),
            )
        }
    }

    fun observeWorks(creatorKey: String): Flow<List<CreatorWork>> = combine(
        workDao.observeForCreator(creatorKey),
        taskRepository.observeAll(),
        batchDao.observeBatches(),
        batchDao.observeWorks(),
    ) { works, tasks, batches, preparations ->
        val platform = creatorKey.substringBefore(':')
        val byWork = tasks.asSequence()
            .filter { it.authorKey == creatorKey || it.authorKey.isBlank() }
            .filter { it.contentId.isNotBlank() && it.platform.wireValue == platform }
            .groupBy { creatorWorkKey(it.platform, bilibiliWorkId(it.platform, it.contentId)) }
        val creatorBatches = batches.filter { it.creatorKey == creatorKey }.associateBy { it.batchId }
        val latest = preparations.filter { it.batchId in creatorBatches }
            .groupBy { it.workKey }
            .mapValues { (_, entries) -> entries.maxByOrNull { creatorBatches[it.batchId]?.createdAt ?: 0L } }
        works.map { entity ->
            val pending = latest[entity.workKey]?.takeIf { it.taskId.isBlank() }
            val records = byWork[entity.workKey].orEmpty()
            entity.toWork(if (entity.platform == SourcePlatform.BILIBILI.wireValue) representativeCreatorTask(records)
                else records.maxByOrNull(TaskRecord::createdAt)).copy(
                relatedTasks = if (entity.platform == SourcePlatform.BILIBILI.wireValue) records else emptyList(),
                preparation = pending,
                preparationCreatedAt = pending?.let { creatorBatches[it.batchId]?.createdAt } ?: 0L,
            )
        }
    }

    suspend fun deleteInactivePreparations(keys: Set<String>) {
        if (keys.isNotEmpty()) batchDao.deleteInactivePreparations(keys.toList())
    }

    suspend fun getCreator(key: String): CreatorProfile? = creatorDao.get(key)?.toProfile()

    suspend fun findByAccount(platform: SourcePlatform, accountId: String): CreatorProfile? =
        creatorDao.findByAccount(platform.wireValue, accountId.trim())?.toProfile()

    suspend fun getPage(creatorKey: String, pageNumber: Int): List<CreatorWork> {
        val tasks = taskRepository.listForAuthor(creatorKey)
        val byContent = tasks.groupBy { creatorWorkKey(it.platform, bilibiliWorkId(it.platform, it.contentId)) }
        return workDao.listPage(creatorKey, pageNumber).map { entity ->
            entity.withTasks(byContent[entity.workKey].orEmpty())
        }
    }

    suspend fun getPageInfo(creatorKey: String, pageNumber: Int): CreatorCachedPage? =
        pageDao.get(creatorKey, pageNumber)?.let {
            CreatorCachedPage(it.pageNumber, it.cursor, it.nextCursor, it.hasMore)
        }

    suspend fun restorePageAfterAmbiguousRefresh(creatorKey: String, pageNumber: Int) {
        workDao.restorePageAfterAmbiguousRefresh(creatorKey, pageNumber)
    }

    suspend fun getWorks(keys: Collection<String>): List<CreatorWork> {
        if (keys.isEmpty()) return emptyList()
        val entities = workDao.listByKeys(keys.toList())
        val taskList = taskRepository.listForAuthor(entities.firstOrNull()?.creatorKey.orEmpty())
        val byContent = taskList.groupBy { creatorWorkKey(it.platform, bilibiliWorkId(it.platform, it.contentId)) }
        return entities.map { it.withTasks(byContent[it.workKey].orEmpty()) }
    }

    suspend fun upsert(profile: CreatorProfile) {
        creatorDao.upsert(profile.withStableDirectory().toEntity())
    }

    suspend fun upsertFromParse(
        result: ParseResult,
        createAuthorIfMissing: Boolean = false,
    ): String {
        if (result.authorStableId.isBlank()) return ""
        val key = creatorKey(result.platform, result.authorStableId)
        val previous = creatorDao.get(key)?.toProfile()
        if (previous == null && !createAuthorIfMissing) return ""
        val now = System.currentTimeMillis()
        val profile = CreatorProfile(
                key = key,
                platform = result.platform,
                stableId = result.authorStableId,
                accountId = result.authorAccountId.ifBlank { previous?.accountId.orEmpty() },
                profileUrl = result.authorProfileUrl.ifBlank { previous?.profileUrl.orEmpty() },
                directoryName = previous?.directoryName.orEmpty(),
                avatarUrl = result.authorAvatarUrl.ifBlank { previous?.avatarUrl.orEmpty() },
                nickname = result.author.ifBlank { previous?.nickname.orEmpty() }
                    .ifBlank { result.authorAccountId.ifBlank { result.authorStableId } },
                bio = previous?.bio.orEmpty(),
                location = previous?.location.orEmpty(),
                metrics = previous?.metrics.orEmpty(),
                accountStatus = previous?.accountStatus ?: CreatorAccountStatus.UNKNOWN,
                followed = if (createAuthorIfMissing) true else previous?.followed ?: false,
                archived = if (createAuthorIfMissing) false else previous?.archived ?: false,
                addedAt = previous?.addedAt ?: now,
                refreshedAt = previous?.refreshedAt ?: 0L,
                refreshError = previous?.refreshError.orEmpty(),
            )
        creatorDao.upsert(profile.withStableDirectory().toEntity())
        if (result.ok && result.contentId.isNotBlank()) {
            val indexedBili = if (result.platform == SourcePlatform.BILIBILI) workDao.listByKeys(
                listOf(creatorWorkKey(result.platform, bilibiliWorkId(result.platform, result.contentId)))
            ).firstOrNull() else null
            workDao.upsertAll(
                listOf(
                    CreatorWork(
                        key = creatorWorkKey(result.platform, bilibiliWorkId(result.platform, result.contentId)),
                        creatorKey = key,
                        platform = result.platform,
                        contentId = bilibiliWorkId(result.platform, result.contentId),
                        canonicalUrl = if (result.platform == SourcePlatform.BILIBILI) "https://www.bilibili.com/video/${bilibiliWorkId(result.platform, result.contentId)}" else result.canonicalUrl,
                        kind = result.kind,
                        title = result.bilibiliTitle.ifBlank { result.document?.title.orEmpty() }
                            .ifBlank { result.description }
                            .ifBlank { "${result.platform.displayName}作品 ${result.contentId}" },
                        coverUrl = result.coverUrl,
                        approximateBytes = result.variants.maxOfOrNull(MediaVariant::size) ?: 0L,
                        lastSeenAt = now,
                        pageNumber = indexedBili?.pageNumber ?: HISTORY_ONLY_PAGE,
                    ).toEntity(),
                ),
            )
        }
        return key
    }

    suspend fun savePage(page: CreatorPage) {
        val previous = creatorDao.get(page.profile.key)?.toProfile()
        creatorDao.upsert(
            page.profile.copy(
                directoryName = previous?.directoryName.orEmpty()
                    .ifBlank { page.profile.directoryName },
                followed = previous?.followed ?: page.profile.followed,
                archived = previous?.archived ?: page.profile.archived,
                addedAt = previous?.addedAt ?: page.profile.addedAt,
            ).withStableDirectory().toEntity(),
        )
        val seen = page.works.map(CreatorWork::key)
        if (seen.isEmpty()) {
            workDao.markPageNotDetected(page.profile.key, page.pageNumber)
        } else {
            workDao.markPageNotDetectedExcept(page.profile.key, page.pageNumber, seen)
        }
        val indexed = workDao.listByKeys(seen).associateBy(CreatorWorkEntity::workKey)
        if (page.works.isNotEmpty()) workDao.upsertAll(page.works.map { work ->
            val existing = indexed[work.key]
            // A Reel/pinned media may appear again on a later page. Update metadata without
            // moving its already-visible card out of the earlier cached page.
            if (page.profile.platform in setOf(SourcePlatform.X, SourcePlatform.INSTAGRAM) &&
                existing != null && existing.pageNumber < page.pageNumber &&
                existing.remoteStatus == CreatorWorkRemoteStatus.PUBLIC.wireValue) {
                work.copy(pageNumber = existing.pageNumber).toEntity()
            } else work.toEntity()
        })
        pageDao.upsert(
            CreatorPageEntity(
                creatorKey = page.profile.key,
                pageNumber = page.pageNumber,
                cursor = page.cursor,
                nextCursor = page.nextCursor,
                hasMore = page.hasMore,
            ),
        )
    }

    suspend fun updateRemoteStatus(workKey: String, status: CreatorWorkRemoteStatus) {
        workDao.updateRemoteStatus(workKey, status.wireValue)
    }

    suspend fun recordRefreshFailure(
        profile: CreatorProfile,
        message: String,
        status: CreatorAccountStatus = CreatorAccountStatus.REFRESH_FAILED,
    ) {
        creatorDao.upsert(
            profile.copy(
                accountStatus = status,
                refreshError = Redactor.sanitize(message),
            ).toEntity(),
        )
    }

    suspend fun setFollowed(profile: CreatorProfile, followed: Boolean, hasDownloads: Boolean) {
        if (followed) {
            creatorDao.setFollowState(profile.key, followed = true, archived = false)
        } else if (hasDownloads) {
            creatorDao.setFollowState(profile.key, followed = false, archived = true)
        } else {
            batchDao.deleteWorksForCreator(profile.key)
            batchDao.deleteForCreator(profile.key)
            workDao.deleteForCreator(profile.key)
            pageDao.deleteForCreator(profile.key)
            creatorDao.delete(profile.key)
        }
    }

    suspend fun deleteCreatorRecords(profile: CreatorProfile) {
        batchDao.deleteWorksForCreator(profile.key)
        batchDao.deleteForCreator(profile.key)
        workDao.deleteForCreator(profile.key)
        pageDao.deleteForCreator(profile.key)
        creatorDao.delete(profile.key)
    }

    private companion object {
        const val HISTORY_ONLY_PAGE = 0
    }
}

private fun CreatorProfile.withStableDirectory(): CreatorProfile = if (directoryName.isNotBlank()) {
    this
} else {
    copy(directoryName = creatorDirectoryName(this))
}

private fun CreatorWorkEntity.withTasks(records: List<TaskRecord>): CreatorWork =
    if (platform == SourcePlatform.BILIBILI.wireValue) toWork(representativeCreatorTask(records)).copy(relatedTasks = records)
    else toWork(records.maxByOrNull(TaskRecord::createdAt))
