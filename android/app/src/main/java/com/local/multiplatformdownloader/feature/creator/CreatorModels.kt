package com.local.multiplatformdownloader.feature.creator


import com.local.multiplatformdownloader.core.model.FileState
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.settings.BatchVideoQuality

import org.json.JSONArray
import org.json.JSONObject

enum class CreatorAccountStatus(val wireValue: String) {
    PUBLIC("PUBLIC"),
    RESTRICTED("RESTRICTED"),
    DEACTIVATED("DEACTIVATED"),
    INACCESSIBLE("INACCESSIBLE"),
    UNKNOWN("UNKNOWN"),
    REFRESH_FAILED("REFRESH_FAILED");

    companion object {
        fun fromWire(value: String): CreatorAccountStatus = entries.firstOrNull {
            it.wireValue == value
        } ?: UNKNOWN
    }
}

enum class CreatorWorkRemoteStatus(val wireValue: String) {
    PUBLIC("PUBLIC"),
    NOT_DETECTED("NOT_DETECTED"),
    UNAVAILABLE("UNAVAILABLE"),
    CHECK_FAILED("CHECK_FAILED"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromWire(value: String): CreatorWorkRemoteStatus = entries.firstOrNull {
            it.wireValue == value
        } ?: UNKNOWN
    }
}

enum class CreatorWorkLocalStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    AVAILABLE,
    PARTIAL,
    DELETED,
    FAILED,
}

data class CreatorMetric(val label: String, val value: String)

data class CreatorProfile(
    val key: String,
    val platform: SourcePlatform,
    val stableId: String,
    val accountId: String = "",
    val profileUrl: String,
    val directoryName: String = "",
    val avatarUrl: String = "",
    val nickname: String,
    val bio: String = "",
    val location: String = "",
    val metrics: List<CreatorMetric> = emptyList(),
    val accountStatus: CreatorAccountStatus = CreatorAccountStatus.PUBLIC,
    val followed: Boolean = true,
    val archived: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val refreshedAt: Long = 0L,
    val refreshError: String = "",
)

data class CreatorWork(
    val key: String,
    val creatorKey: String,
    val platform: SourcePlatform,
    val contentId: String,
    val canonicalUrl: String,
    val kind: MediaKind,
    val title: String,
    val coverUrl: String = "",
    val publishedAt: Long = 0L,
    val durationMs: Long = 0L,
    val approximateBytes: Long = 0L,
    val remoteStatus: CreatorWorkRemoteStatus = CreatorWorkRemoteStatus.PUBLIC,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val pageNumber: Int = 1,
    val task: TaskRecord? = null,
    val preparation: BatchWorkEntity? = null,
    val preparationCreatedAt: Long = 0L,
    val relatedTasks: List<TaskRecord> = emptyList(),
) {
    val hasLocalRecord: Boolean get() = task != null || preparation != null
    val hasLocalContent: Boolean get() = relatedTasks.ifEmpty { listOfNotNull(task) }.any { record ->
        record.outputs.isNotEmpty() || record.status == TaskStatus.COMPLETE ||
            record.fileState !in setOf(FileState.UNKNOWN, FileState.STORAGE_UNAVAILABLE)
    }
    val preparationFailed: Boolean get() = task == null && preparation?.status == CreatorBatchWorkStatus.FAILED
    val preparationActionable: Boolean get() = task == null && preparation?.taskId?.isBlank() == true &&
        preparation.status in setOf(CreatorBatchWorkStatus.FAILED, CreatorBatchWorkStatus.PAUSED)

    val localStatus: CreatorWorkLocalStatus
        get() = when {
            preparationFailed -> CreatorWorkLocalStatus.FAILED
            task == null && preparation != null -> CreatorWorkLocalStatus.QUEUED
            task == null -> CreatorWorkLocalStatus.NOT_DOWNLOADED
            task.status == TaskStatus.QUEUED -> CreatorWorkLocalStatus.QUEUED
            task.status == TaskStatus.RUNNING -> CreatorWorkLocalStatus.DOWNLOADING
            task.status == TaskStatus.FAILED -> CreatorWorkLocalStatus.FAILED
            task.fileState == FileState.AVAILABLE -> CreatorWorkLocalStatus.AVAILABLE
            task.fileState in setOf(FileState.PARTIAL, FileState.DELETE_FAILED) ->
                CreatorWorkLocalStatus.PARTIAL
            task.fileState == FileState.MISSING -> CreatorWorkLocalStatus.DELETED
            else -> CreatorWorkLocalStatus.NOT_DOWNLOADED
        }

    val shouldSkipInSelectAll: Boolean
        get() = localStatus == CreatorWorkLocalStatus.AVAILABLE
}

internal fun creatorLocalDownloadBytes(tasks: List<TaskRecord>): Long = tasks.asSequence()
    .filterNot { it.status == TaskStatus.DELETING }
    .filterNot { it.fileState in setOf(FileState.MISSING, FileState.STORAGE_UNAVAILABLE) }
    .flatMap { it.outputs.asSequence() }
    .distinctBy { it.uri }
    .sumOf { it.sizeBytes.coerceAtLeast(0L) }

data class CreatorPage(
    val profile: CreatorProfile,
    val works: List<CreatorWork>,
    val pageNumber: Int = 1,
    val cursor: String = "",
    val nextCursor: String = "",
    val hasMore: Boolean = false,
)

data class CreatorCachedPage(
    val pageNumber: Int,
    val cursor: String,
    val nextCursor: String,
    val hasMore: Boolean,
)

data class BatchSizeEstimate(
    val minimumBytes: Long,
    val maximumBytes: Long,
    val knownItems: Int,
    val estimatedItems: Int,
)

internal val CREATOR_BATCH_PLATFORMS = listOf(
    SourcePlatform.DOUYIN,
    SourcePlatform.ZHIHU,
    SourcePlatform.X,
    SourcePlatform.INSTAGRAM,
    SourcePlatform.BILIBILI,
)

internal val CREATOR_LIBRARY_PLATFORMS = SourcePlatform.entries.toList()

internal fun creatorKey(platform: SourcePlatform, stableId: String): String =
    "${platform.wireValue}:${stableId.trim()}"

internal fun creatorWorkKey(platform: SourcePlatform, contentId: String): String =
    "${platform.wireValue}:${contentId.trim()}"

internal fun taskCreatorKey(task: TaskRecord): String = task.authorKey.ifBlank {
    task.authorStableId.takeIf(String::isNotBlank)?.let { creatorKey(task.platform, it) }.orEmpty()
}

internal fun List<CreatorMetric>.toMetricsJson(): String = JSONArray().apply {
    forEach { metric -> put(JSONObject().put("label", metric.label).put("value", metric.value)) }
}.toString()

internal fun metricsFromJson(value: String): List<CreatorMetric> = runCatching {
    val array = JSONArray(value)
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        CreatorMetric(item.optString("label"), item.optString("value"))
            .takeIf { it.label.isNotBlank() && it.value.isNotBlank() }
    }
}.getOrDefault(emptyList())

internal fun selectCurrentCreatorPage(
    selected: Set<String>,
    currentPage: List<CreatorWork>,
): Set<String> = selected + currentPage.asSequence()
    .filterNot(CreatorWork::shouldSkipInSelectAll)
    .map(CreatorWork::key)
    .toSet()

internal fun filterCreatorsByPlatform(
    creators: List<CreatorProfile>,
    platform: SourcePlatform?,
): List<CreatorProfile> = creators
    .filter { it.platform in CREATOR_LIBRARY_PLATFORMS }
    .let { supported ->
        if (platform == null) supported else supported.filter { it.platform == platform }
    }

internal fun visibleCreatorPage(
    works: List<CreatorWork>,
    pageNumber: Int,
    limit: Int = Int.MAX_VALUE,
): List<CreatorWork> = works.filter { it.pageNumber == pageNumber }.take(limit)

internal fun chooseBatchVariant(
    variants: List<MediaVariant>,
    quality: BatchVideoQuality,
    preferH264: Boolean,
): Int {
    if (variants.isEmpty()) return 0
    val maxHeight = quality.maxHeight
    val eligible = if (maxHeight == null) variants.indices.toList() else {
        variants.indices.filter { variants[it].height in 1..maxHeight }
            .ifEmpty { variants.indices.toList() }
    }
    return eligible.maxWithOrNull(
        compareBy<Int> { variants[it].width.toLong() * variants[it].height }
            .thenBy { variants[it].fps }
            .thenBy { variants[it].bitrate }
            .thenBy { if (preferH264 && variants[it].codec.contains("264")) 1 else 0 },
    ) ?: 0
}

internal fun estimateBatchSize(
    works: List<CreatorWork>,
    quality: BatchVideoQuality,
): BatchSizeEstimate {
    var minimum = 0L
    var maximum = 0L
    var known = 0
    var estimated = 0
    works.forEach { work ->
        if (work.approximateBytes > 0L) {
            minimum += (work.approximateBytes * 85L) / 100L
            maximum += (work.approximateBytes * 115L) / 100L
            known += 1
        } else {
            val middle = when (work.kind) {
                MediaKind.IMAGE -> 8L * 1024L * 1024L
                MediaKind.DOCUMENT -> 12L * 1024L * 1024L
                MediaKind.VIDEO -> {
                    val seconds = (work.durationMs / 1_000L).coerceAtLeast(30L)
                    val bitsPerSecond = when (quality) {
                        BatchVideoQuality.UP_TO_720P -> 2_500_000L
                        BatchVideoQuality.UP_TO_1080P -> 5_000_000L
                        BatchVideoQuality.HIGHEST -> 8_000_000L
                    }
                    seconds * bitsPerSecond / 8L
                }
            }
            minimum += middle / 2L
            maximum += middle * 3L / 2L
            estimated += 1
        }
    }
    return BatchSizeEstimate(minimum, maximum, known, estimated)
}
