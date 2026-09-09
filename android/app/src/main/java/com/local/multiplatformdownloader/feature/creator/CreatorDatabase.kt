package com.local.multiplatformdownloader.feature.creator

import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "creators")
data class CreatorEntity(
    @PrimaryKey @ColumnInfo(name = "creator_key") val creatorKey: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "stable_id") val stableId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "profile_url") val profileUrl: String,
    @ColumnInfo(name = "directory_name") val directoryName: String,
    @ColumnInfo(name = "avatar_url") val avatarUrl: String,
    @ColumnInfo(name = "nickname") val nickname: String,
    @ColumnInfo(name = "bio") val bio: String,
    @ColumnInfo(name = "location") val location: String,
    @ColumnInfo(name = "metrics_json") val metricsJson: String,
    @ColumnInfo(name = "account_status") val accountStatus: String,
    @ColumnInfo(name = "followed") val followed: Boolean,
    @ColumnInfo(name = "archived") val archived: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "refreshed_at") val refreshedAt: Long,
    @ColumnInfo(name = "refresh_error") val refreshError: String,
)

@Entity(tableName = "creator_works", indices = [Index("creator_key")])
data class CreatorWorkEntity(
    @PrimaryKey @ColumnInfo(name = "work_key") val workKey: String,
    @ColumnInfo(name = "creator_key") val creatorKey: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "canonical_url") val canonicalUrl: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String,
    @ColumnInfo(name = "published_at") val publishedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "approximate_bytes") val approximateBytes: Long,
    @ColumnInfo(name = "remote_status") val remoteStatus: String,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "page_number") val pageNumber: Int,
)

@Entity(tableName = "download_batches")
data class DownloadBatchEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "creator_key") val creatorKey: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "settings_json") val settingsJson: String,
    @ColumnInfo(name = "selected_count") val selectedCount: Int,
)

@Entity(primaryKeys = ["batch_id", "work_key"], tableName = "batch_works")
data class BatchWorkEntity(
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "work_key") val workKey: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "error") val error: String,
    @ColumnInfo(name = "source_url", defaultValue = "''") val sourceUrl: String = "",
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
)

@Entity(primaryKeys = ["creator_key", "page_number"], tableName = "creator_pages")
data class CreatorPageEntity(
    @ColumnInfo(name = "creator_key") val creatorKey: String,
    @ColumnInfo(name = "page_number") val pageNumber: Int,
    @ColumnInfo(name = "cursor") val cursor: String,
    @ColumnInfo(name = "next_cursor") val nextCursor: String,
    @ColumnInfo(name = "has_more") val hasMore: Boolean,
)

@Dao
interface CreatorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CreatorEntity)

    @Query("SELECT * FROM creators ORDER BY archived ASC, followed DESC, added_at DESC")
    fun observeAll(): Flow<List<CreatorEntity>>

    @Query("SELECT * FROM creators WHERE creator_key = :key LIMIT 1")
    suspend fun get(key: String): CreatorEntity?

    @Query("SELECT * FROM creators WHERE platform = :platform AND account_id = :accountId LIMIT 1")
    suspend fun findByAccount(platform: String, accountId: String): CreatorEntity?

    @Query("UPDATE creators SET followed = :followed, archived = :archived WHERE creator_key = :key")
    suspend fun setFollowState(key: String, followed: Boolean, archived: Boolean)

    @Query("DELETE FROM creators WHERE creator_key = :key")
    suspend fun delete(key: String)
}

@Dao
interface CreatorWorkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CreatorWorkEntity>)

    @Query("SELECT * FROM creator_works WHERE creator_key = :creatorKey ORDER BY published_at DESC, last_seen_at DESC")
    fun observeForCreator(creatorKey: String): Flow<List<CreatorWorkEntity>>

    @Query("SELECT * FROM creator_works")
    fun observeAll(): Flow<List<CreatorWorkEntity>>

    @Query("SELECT * FROM creator_works WHERE creator_key = :creatorKey ORDER BY published_at DESC, last_seen_at DESC")
    suspend fun listForCreator(creatorKey: String): List<CreatorWorkEntity>

    @Query("SELECT * FROM creator_works WHERE creator_key = :creatorKey AND page_number = :pageNumber AND remote_status = 'PUBLIC' ORDER BY published_at DESC, last_seen_at DESC")
    suspend fun listPage(creatorKey: String, pageNumber: Int): List<CreatorWorkEntity>

    @Query("SELECT * FROM creator_works WHERE work_key IN (:keys)")
    suspend fun listByKeys(keys: List<String>): List<CreatorWorkEntity>

    @Query("DELETE FROM creator_works WHERE creator_key = :creatorKey")
    suspend fun deleteForCreator(creatorKey: String)

    @Query("UPDATE creator_works SET remote_status = 'NOT_DETECTED' WHERE creator_key = :creatorKey AND page_number = :pageNumber AND work_key NOT IN (:seenKeys)")
    suspend fun markPageNotDetectedExcept(creatorKey: String, pageNumber: Int, seenKeys: List<String>)

    @Query("UPDATE creator_works SET remote_status = 'NOT_DETECTED' WHERE creator_key = :creatorKey AND page_number = :pageNumber")
    suspend fun markPageNotDetected(creatorKey: String, pageNumber: Int)

    @Query("UPDATE creator_works SET remote_status = 'PUBLIC' WHERE creator_key = :creatorKey AND page_number = :pageNumber AND remote_status = 'NOT_DETECTED'")
    suspend fun restorePageAfterAmbiguousRefresh(creatorKey: String, pageNumber: Int)

    @Query("UPDATE creator_works SET remote_status = :status WHERE work_key = :workKey")
    suspend fun updateRemoteStatus(workKey: String, status: String)
}

@Dao
interface CreatorPageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CreatorPageEntity)

    @Query("SELECT * FROM creator_pages WHERE creator_key = :creatorKey AND page_number = :pageNumber LIMIT 1")
    suspend fun get(creatorKey: String, pageNumber: Int): CreatorPageEntity?

    @Query("DELETE FROM creator_pages WHERE creator_key = :creatorKey")
    suspend fun deleteForCreator(creatorKey: String)
}

@Dao
interface DownloadBatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(entity: DownloadBatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorks(entities: List<BatchWorkEntity>)

    @Query("SELECT * FROM download_batches WHERE batch_id = :batchId LIMIT 1")
    suspend fun getBatch(batchId: String): DownloadBatchEntity?

    @Query("SELECT * FROM batch_works WHERE batch_id = :batchId ORDER BY rowid")
    suspend fun listWorks(batchId: String): List<BatchWorkEntity>

    @Query("SELECT * FROM download_batches ORDER BY created_at DESC")
    fun observeBatches(): Flow<List<DownloadBatchEntity>>

    @Query("SELECT * FROM batch_works")
    fun observeWorks(): Flow<List<BatchWorkEntity>>

    @Query("DELETE FROM batch_works WHERE work_key IN (:keys) AND status IN ('FAILED', 'PAUSED') AND task_id = '' AND batch_id != :retainedBatchId")
    suspend fun deleteInactivePreparations(keys: List<String>, retainedBatchId: String = "")

    @Query("DELETE FROM batch_works WHERE batch_id = :batchId")
    suspend fun deleteWorksForBatch(batchId: String)

    @Query("DELETE FROM download_batches WHERE batch_id = :batchId")
    suspend fun deleteBatch(batchId: String)

    @Query("SELECT * FROM download_batches WHERE creator_key = :creatorKey AND status IN ('PAUSED', 'WAITING_FOREGROUND') AND EXISTS (SELECT 1 FROM batch_works WHERE batch_works.batch_id = download_batches.batch_id AND batch_works.status IN ('PAUSED', 'WEB_REQUIRED', 'PARSING')) ORDER BY created_at DESC LIMIT 1")
    suspend fun latestPaused(creatorKey: String): DownloadBatchEntity?

    @Query("SELECT * FROM download_batches WHERE creator_key = :creatorKey")
    suspend fun listForCreator(creatorKey: String): List<DownloadBatchEntity>

    @Query("DELETE FROM batch_works WHERE batch_id IN (SELECT batch_id FROM download_batches WHERE creator_key = :creatorKey)")
    suspend fun deleteWorksForCreator(creatorKey: String)

    @Query("DELETE FROM download_batches WHERE creator_key = :creatorKey")
    suspend fun deleteForCreator(creatorKey: String)

    @Query("UPDATE batch_works SET status = :status, task_id = :taskId, error = :error WHERE batch_id = :batchId AND work_key = :workKey")
    suspend fun updateWork(batchId: String, workKey: String, status: String, taskId: String, error: String)

    @Query("UPDATE batch_works SET source_url = :sourceUrl WHERE batch_id = :batchId AND work_key = :workKey")
    suspend fun updateWorkSource(batchId: String, workKey: String, sourceUrl: String)

    @Query("UPDATE batch_works SET status = 'WEB_REQUIRED', source_url = :sourceUrl, error = :error WHERE batch_id = :batchId AND work_key = :workKey")
    suspend fun requireWeb(
        batchId: String,
        workKey: String,
        sourceUrl: String,
        error: String,
    )

    @Query("UPDATE batch_works SET status = 'PARSING', attempt_count = attempt_count + 1, error = '' WHERE batch_id = :batchId AND work_key = :workKey AND status = 'WEB_REQUIRED'")
    suspend fun markWebParsing(batchId: String, workKey: String): Int

    @Query("SELECT * FROM batch_works WHERE batch_id = :batchId AND work_key = :workKey LIMIT 1")
    suspend fun getWork(batchId: String, workKey: String): BatchWorkEntity?

    @Query("UPDATE batch_works SET status = 'PARSING_HTTP' WHERE batch_id = :batchId AND work_key = :workKey AND status IN ('QUEUED', 'PAUSED')")
    suspend fun claimHttpPreparation(batchId: String, workKey: String): Int

    @Query("UPDATE batch_works SET status = 'QUEUED', error = '' WHERE batch_id = :batchId AND work_key = :workKey AND status = 'PAUSED' AND task_id = ''")
    suspend fun queuePausedPreparation(batchId: String, workKey: String): Int

    @Query("UPDATE batch_works SET status = 'QUEUED' WHERE batch_id = :batchId AND status = 'PARSING_HTTP'")
    suspend fun recoverHttpPreparation(batchId: String)

    @Query("UPDATE batch_works SET status = 'WEB_REQUIRED' WHERE batch_id = :batchId AND status = 'PARSING'")
    suspend fun recoverWebParsing(batchId: String)

    @Query("UPDATE download_batches SET status = :status WHERE batch_id = :batchId")
    suspend fun updateBatch(batchId: String, status: String)
}

internal fun CreatorEntity.toProfile(): CreatorProfile = CreatorProfile(
    key = creatorKey,
    platform = SourcePlatform.fromWire(platform),
    stableId = stableId,
    accountId = accountId,
    profileUrl = profileUrl,
    directoryName = directoryName,
    avatarUrl = avatarUrl,
    nickname = nickname,
    bio = bio,
    location = location,
    metrics = metricsFromJson(metricsJson),
    accountStatus = CreatorAccountStatus.fromWire(accountStatus),
    followed = followed,
    archived = archived,
    addedAt = addedAt,
    refreshedAt = refreshedAt,
    refreshError = refreshError,
)

internal fun CreatorProfile.toEntity(): CreatorEntity = CreatorEntity(
    creatorKey = key,
    platform = platform.wireValue,
    stableId = stableId,
    accountId = accountId,
    profileUrl = profileUrl,
    directoryName = directoryName,
    avatarUrl = avatarUrl,
    nickname = nickname,
    bio = bio,
    location = location,
    metricsJson = metrics.toMetricsJson(),
    accountStatus = accountStatus.wireValue,
    followed = followed,
    archived = archived,
    addedAt = addedAt,
    refreshedAt = refreshedAt,
    refreshError = refreshError,
)

internal fun CreatorWorkEntity.toWork(task: TaskRecord? = null): CreatorWork = CreatorWork(
    key = workKey,
    creatorKey = creatorKey,
    platform = SourcePlatform.fromWire(platform),
    contentId = contentId,
    canonicalUrl = canonicalUrl,
    kind = MediaKind.fromWire(kind),
    title = title,
    coverUrl = coverUrl,
    publishedAt = publishedAt,
    durationMs = durationMs,
    approximateBytes = approximateBytes,
    remoteStatus = CreatorWorkRemoteStatus.fromWire(remoteStatus),
    lastSeenAt = lastSeenAt,
    pageNumber = pageNumber,
    task = task,
)

internal fun CreatorWork.toEntity(): CreatorWorkEntity = CreatorWorkEntity(
    workKey = key,
    creatorKey = creatorKey,
    platform = platform.wireValue,
    contentId = contentId,
    canonicalUrl = canonicalUrl,
    kind = kind.wireValue,
    title = title,
    coverUrl = coverUrl,
    publishedAt = publishedAt,
    durationMs = durationMs,
    approximateBytes = approximateBytes,
    remoteStatus = remoteStatus.wireValue,
    lastSeenAt = lastSeenAt,
    pageNumber = pageNumber,
)
