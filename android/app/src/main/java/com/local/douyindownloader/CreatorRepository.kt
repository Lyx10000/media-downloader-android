package com.local.douyindownloader

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class CreatorBatchSummary(
    val selected: Int,
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
        val tasksById = tasks.associateBy(TaskRecord::id)
        val latest = batches.distinctBy(DownloadBatchEntity::creatorKey)
        latest.associate { batch ->
            val entries = works.filter { it.batchId == batch.batchId }
            batch.creatorKey to CreatorBatchSummary(
                selected = batch.selectedCount,
                queued = entries.count { entry ->
                    entry.status in setOf(
                        CreatorBatchWorkStatus.QUEUED,
                        CreatorBatchWorkStatus.PARSING,
                        CreatorBatchWorkStatus.WEB_REQUIRED,
                        CreatorBatchWorkStatus.PREPARED,
                    ) || entry.status == CreatorBatchWorkStatus.SCHEDULED &&
                        tasksById[entry.taskId]?.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)
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
                    tasksById[entry.taskId]?.status == TaskStatus.COMPLETE
                },
                failed = entries.count { entry ->
                    entry.status == "FAILED" ||
                        tasksById[entry.taskId]?.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)
                },
            )
        }
    }

    fun observeBatchPreparations(): Flow<Map<String, CreatorBatchPreparation>> = combine(
        batchDao.observeBatches(),
        batchDao.observeWorks(),
    ) { batches, works ->
        batches.distinctBy(DownloadBatchEntity::creatorKey).associate { batch ->
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
    ) { works, tasks ->
        val platform = creatorKey.substringBefore(':')
        val byWork = tasks.asSequence()
            .filter { it.authorKey == creatorKey || it.authorKey.isBlank() }
            .filter { it.contentId.isNotBlank() && it.platform.wireValue == platform }
            .groupBy { creatorWorkKey(it.platform, it.contentId) }
            .mapValues { (_, records) -> records.maxByOrNull(TaskRecord::createdAt) }
        works.map { entity -> entity.toWork(byWork[entity.workKey]) }
    }

    suspend fun getCreator(key: String): CreatorProfile? = creatorDao.get(key)?.toProfile()

    suspend fun findByAccount(platform: SourcePlatform, accountId: String): CreatorProfile? =
        creatorDao.findByAccount(platform.wireValue, accountId.trim())?.toProfile()

    suspend fun getPage(creatorKey: String, pageNumber: Int): List<CreatorWork> {
        val tasks = taskRepository.listForAuthor(creatorKey)
        val byContent = tasks.groupBy { creatorWorkKey(it.platform, it.contentId) }
            .mapValues { (_, records) -> records.maxByOrNull(TaskRecord::createdAt) }
        return workDao.listPage(creatorKey, pageNumber).map { entity ->
            entity.toWork(byContent[entity.workKey])
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
        val byContent = taskList.groupBy { creatorWorkKey(it.platform, it.contentId) }
            .mapValues { (_, records) -> records.maxByOrNull(TaskRecord::createdAt) }
        return entities.map { it.toWork(byContent[it.workKey]) }
    }

    suspend fun upsert(profile: CreatorProfile) {
        creatorDao.upsert(profile.withStableDirectory().toEntity())
    }

    suspend fun upsertFromParse(result: ParseResult): String {
        if (result.authorStableId.isBlank()) return ""
        val key = creatorKey(result.platform, result.authorStableId)
        val previous = creatorDao.get(key)?.toProfile()
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
                followed = previous?.followed ?: false,
                // A directly downloaded work still needs a visible author-history entry,
                // but it must not silently opt the user into following/refreshing that author.
                archived = previous?.archived ?: true,
                addedAt = previous?.addedAt ?: now,
                refreshedAt = previous?.refreshedAt ?: 0L,
                refreshError = previous?.refreshError.orEmpty(),
            )
        creatorDao.upsert(profile.withStableDirectory().toEntity())
        if (result.ok && result.contentId.isNotBlank()) {
            workDao.upsertAll(
                listOf(
                    CreatorWork(
                        key = creatorWorkKey(result.platform, result.contentId),
                        creatorKey = key,
                        platform = result.platform,
                        contentId = result.contentId,
                        canonicalUrl = result.canonicalUrl,
                        kind = result.kind,
                        title = result.document?.title.orEmpty()
                            .ifBlank { result.description }
                            .ifBlank { "${result.platform.displayName}作品 ${result.contentId}" },
                        coverUrl = result.coverUrl,
                        approximateBytes = result.variants.maxOfOrNull(MediaVariant::size) ?: 0L,
                        lastSeenAt = now,
                        pageNumber = HISTORY_ONLY_PAGE,
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
        if (page.works.isNotEmpty()) workDao.upsertAll(page.works.map(CreatorWork::toEntity))
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
