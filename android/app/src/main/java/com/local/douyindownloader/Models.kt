package com.local.douyindownloader

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class StorageMode(val wireValue: String) {
    DEFAULT("DEFAULT"),
    SAF("SAF"),
    LEGACY("LEGACY");

    companion object {
        fun fromWire(value: String): StorageMode = entries.firstOrNull { it.wireValue == value }
            ?: LEGACY
    }
}

enum class FileState(val wireValue: String) {
    UNKNOWN("UNKNOWN"),
    AVAILABLE("AVAILABLE"),
    PARTIAL("PARTIAL"),
    MISSING("MISSING"),
    STORAGE_UNAVAILABLE("STORAGE_UNAVAILABLE"),
    DELETE_FAILED("DELETE_FAILED");

    companion object {
        fun fromWire(value: String): FileState = entries.firstOrNull { it.wireValue == value }
            ?: UNKNOWN
    }
}

@JvmInline
value class TaskStatus private constructor(val wireValue: String) {
    companion object {
        val QUEUED = TaskStatus("QUEUED")
        val RUNNING = TaskStatus("RUNNING")
        val COMPLETE = TaskStatus("COMPLETE")
        val FAILED = TaskStatus("FAILED")
        val CANCELLED = TaskStatus("CANCELLED")
        val DELETING = TaskStatus("DELETING")

        fun fromWire(value: String): TaskStatus = when (value) {
            QUEUED.wireValue -> QUEUED
            RUNNING.wireValue -> RUNNING
            COMPLETE.wireValue -> COMPLETE
            FAILED.wireValue -> FAILED
            CANCELLED.wireValue -> CANCELLED
            DELETING.wireValue -> DELETING
            else -> TaskStatus(value)
        }
    }
}

enum class DownloadMode(val wireValue: String) {
    MERGE_KEEP("merge_keep"),
    TRACKS("tracks"),
    VIDEO_ONLY("video_only"),
    AUDIO_ONLY("audio_only");

    companion object {
        fun fromWire(value: String): DownloadMode = entries.firstOrNull { it.wireValue == value }
            ?: MERGE_KEEP
    }
}

enum class MediaKind(val wireValue: String) {
    VIDEO("video"),
    IMAGE("image"),
    DOCUMENT("document");

    companion object {
        fun fromWire(value: String): MediaKind = entries.firstOrNull { it.wireValue == value }
            ?: VIDEO
    }
}

data class MediaVariant(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val fps: Int,
    val codec: String,
    val size: Long,
    val sizeSource: String,
    val urls: List<String>,
) {
    val label: String
        get() {
            val byteSize = size
            return buildList {
                add(if (width > 0 && height > 0) "${width}×${height}" else "分辨率未知")
                if (bitrate > 0) add("%.2f Mbps".format(bitrate / 1_000_000.0))
                if (fps > 0) add("$fps fps")
                if (codec.isNotBlank()) add(codec)
                when {
                    byteSize <= 0 -> add("大小未知")
                    sizeSource == "estimated" -> add("约 %.1f MB".format(byteSize / 1048576.0))
                    else -> add("%.1f MB".format(byteSize / 1048576.0))
                }
            }.joinToString(" / ")
        }

    fun toJson() = JSONObject().apply {
        put("width", width)
        put("height", height)
        put("bitrate", bitrate)
        put("fps", fps)
        put("codec", codec)
        put("size", size)
        put("size_source", sizeSource)
        put("urls", JSONArray(urls))
    }
}

data class ParserAttempt(
    val strategy: String,
    val selected: Boolean,
    val statusCode: Int = 0,
    val errorCode: String = "",
)

data class ParseResult(
    val ok: Boolean,
    val platform: SourcePlatform = SourcePlatform.DOUYIN,
    val contentId: String = "",
    val canonicalUrl: String = "",
    val referer: String = platform.referer,
    val kind: MediaKind = MediaKind.VIDEO,
    val author: String = "",
    val authorAccountId: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val variants: List<MediaVariant> = emptyList(),
    val audioUrls: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val imageCandidates: List<List<String>> = emptyList(),
    val musicUrls: List<String> = emptyList(),
    val document: DocumentContent? = null,
    val responseShape: String = "{}",
    val errorCode: String = "",
    val message: String = "",
    val rawJson: String = "{}",
    val parserAttempts: List<ParserAttempt> = emptyList(),
) {
    fun toJson(): JSONObject {
        val preserved = runCatching { JSONObject(rawJson) }.getOrNull()
        if (preserved?.has("ok") == true) return preserved
        return JSONObject().apply {
                put("ok", ok)
                put("platform", platform.wireValue)
                put("content_id", contentId)
                if (platform == SourcePlatform.DOUYIN) put("aweme_id", contentId)
                put("canonical_url", canonicalUrl)
                put("referer", referer)
                put("kind", kind.wireValue)
                put("author", author)
                put("author_account_id", authorAccountId)
                put("description", description)
                put("cover_url", coverUrl)
                put("variants", JSONArray().apply { variants.forEach { put(it.toJson()) } })
                put("audio_urls", JSONArray(audioUrls))
                put("image_urls", JSONArray(imageUrls))
                put(
                    "image_candidates",
                    JSONArray().apply {
                        imageCandidates.forEach { candidates -> put(JSONArray(candidates)) }
                    },
                )
                put("music_urls", JSONArray(musicUrls))
                document?.let { put("document", it.toJson()) }
                put(
                    "response_shape",
                    runCatching { JSONObject(responseShape) }.getOrElse { JSONObject() },
                )
                put("error_code", errorCode)
                put("message", message)
        }
    }

    companion object {
        fun fromJson(text: String): ParseResult {
            val root = JSONObject(text)
            if (!root.optBoolean("ok")) {
                return ParseResult(
                    ok = false,
                    platform = SourcePlatform.fromWire(root.optString("platform", "douyin")),
                    errorCode = root.optString("error_code", "PARSE_FAILED"),
                    message = root.optString("message", "解析失败"),
                    rawJson = text,
                )
            }
            val variants = root.optJSONArray("variants").toObjects { item ->
                val size = item.optLong("size")
                MediaVariant(
                    width = item.optInt("width"),
                    height = item.optInt("height"),
                    bitrate = item.optInt("bitrate"),
                    fps = item.optInt("fps"),
                    codec = item.optString("codec"),
                    size = size,
                    sizeSource = item.optString(
                        "size_source",
                        if (size > 0) "api" else "unknown",
                    ),
                    urls = item.optJSONArray("urls").toStrings(),
                )
            }
            val imageUrls = root.optJSONArray("image_urls").toStrings()
            val imageCandidates = root.optJSONArray("image_candidates").toStringLists()
                .ifEmpty { imageUrls.map(::listOf) }
            return ParseResult(
                ok = true,
                platform = SourcePlatform.fromWire(root.optString("platform", "douyin")),
                contentId = root.optString("content_id").ifBlank {
                    root.optString("aweme_id")
                },
                canonicalUrl = root.optString("canonical_url"),
                referer = root.optString("referer").ifBlank {
                    SourcePlatform.fromWire(root.optString("platform", "douyin")).referer
                },
                kind = MediaKind.fromWire(root.optString("kind", MediaKind.VIDEO.wireValue)),
                author = root.optString("author"),
                authorAccountId = root.optString("author_account_id"),
                description = root.optString("description"),
                coverUrl = root.optString("cover_url"),
                variants = variants,
                audioUrls = root.optJSONArray("audio_urls").toStrings(),
                imageUrls = imageCandidates.mapNotNull(List<String>::firstOrNull),
                imageCandidates = imageCandidates,
                musicUrls = root.optJSONArray("music_urls").toStrings(),
                document = root.optJSONObject("document")?.let(DocumentContent::fromJson),
                responseShape = root.optJSONObject("response_shape")?.toString(2) ?: "{}",
                rawJson = text,
            )
        }
    }
}

data class TaskSpec(
    val taskId: String,
    val createdAt: Long,
    val result: ParseResult,
    val variantIndex: Int,
    val mode: DownloadMode,
    val sourceText: String = "",
    val storageMode: StorageMode = StorageMode.LEGACY,
    val storageRoot: String = "",
    val taskFolder: String = taskFolderName(createdAt, taskId),
    val pendingRedownload: PendingRedownload? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("task_id", taskId)
        put("created_at", createdAt)
        put("result", result.toJson())
        put("variant_index", variantIndex)
        put("mode", mode.wireValue)
        put("source_text", sourceText)
        put("storage_mode", storageMode.wireValue)
        put("storage_root", storageRoot)
        put("task_folder", taskFolder)
        pendingRedownload?.let { put("pending_redownload", it.toJson()) }
    }.toString()

    companion object {
        fun fromJson(text: String): TaskSpec {
            val root = JSONObject(text)
            val resultJson = root.getJSONObject("result").toString()
            val createdAt = root.getLong("created_at")
            val taskId = root.getString("task_id")
            return TaskSpec(
                taskId = taskId,
                createdAt = createdAt,
                result = ParseResult.fromJson(resultJson),
                variantIndex = root.optInt("variant_index", 0),
                mode = DownloadMode.fromWire(root.optString("mode", DownloadMode.MERGE_KEEP.wireValue)),
                sourceText = root.optString("source_text"),
                storageMode = StorageMode.fromWire(
                    root.optString("storage_mode", StorageMode.LEGACY.wireValue),
                ),
                storageRoot = root.optString("storage_root"),
                taskFolder = root.optString("task_folder").ifBlank {
                    legacyTaskFolderName(createdAt)
                },
                pendingRedownload = root.optJSONObject("pending_redownload")
                    ?.let(PendingRedownload::fromJson),
            )
        }
    }

    fun executionSpec(): TaskSpec = pendingRedownload?.let { pending ->
        copy(
            result = pending.result,
            variantIndex = pending.variantIndex,
            storageMode = pending.storageMode,
            storageRoot = pending.storageRoot,
            taskFolder = pending.taskFolder,
        )
    } ?: this

    fun committedRedownloadSpec(): TaskSpec = executionSpec().copy(pendingRedownload = null)

    fun stableSource(): String = if (result.canonicalUrl.isNotBlank()) {
        result.canonicalUrl
    } else if (result.platform == SourcePlatform.DOUYIN && result.contentId.isNotBlank()) {
        val path = if (result.kind == MediaKind.IMAGE) "note" else "video"
        "https://www.douyin.com/$path/${result.contentId}"
    } else sourceText
}

data class TaskOutput(
    val uri: String,
    val displayName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
    val relativePath: String = displayName,
) {
    fun toJson() = JSONObject().apply {
        put("uri", uri)
        put("display_name", displayName)
        put("mime_type", mimeType)
        put("size_bytes", sizeBytes)
        put("relative_path", relativePath.ifBlank { displayName })
    }

    companion object {
        fun fromJson(value: Any?): TaskOutput? = when (value) {
            is JSONObject -> TaskOutput(
                uri = value.optString("uri"),
                displayName = value.optString("display_name"),
                mimeType = value.optString("mime_type"),
                sizeBytes = value.optLong("size_bytes").coerceAtLeast(0L),
                relativePath = value.optString("relative_path").ifBlank {
                    value.optString("display_name")
                },
            ).takeIf { it.uri.isNotBlank() }
            is String -> TaskOutput(uri = value).takeIf { it.uri.isNotBlank() }
            else -> null
        }
    }
}

data class PendingRedownload(
    val result: ParseResult,
    val variantIndex: Int,
    val storageMode: StorageMode,
    val storageRoot: String,
    val taskFolder: String,
    val previousOutputs: List<TaskOutput>,
    val previousFileState: FileState,
    val stagedOutputs: List<TaskOutput> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("result", result.toJson())
        put("variant_index", variantIndex)
        put("storage_mode", storageMode.wireValue)
        put("storage_root", storageRoot)
        put("task_folder", taskFolder)
        put("previous_outputs", JSONArray().apply { previousOutputs.forEach { put(it.toJson()) } })
        put("previous_file_state", previousFileState.wireValue)
        put("staged_outputs", JSONArray().apply { stagedOutputs.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(root: JSONObject): PendingRedownload = PendingRedownload(
            result = ParseResult.fromJson(root.getJSONObject("result").toString()),
            variantIndex = root.optInt("variant_index", 0),
            storageMode = StorageMode.fromWire(
                root.optString("storage_mode", StorageMode.LEGACY.wireValue),
            ),
            storageRoot = root.optString("storage_root"),
            taskFolder = root.optString("task_folder"),
            previousOutputs = root.optJSONArray("previous_outputs").let { array ->
                if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
                    TaskOutput.fromJson(array.opt(index))
                }
            },
            previousFileState = FileState.fromWire(
                root.optString("previous_file_state", FileState.UNKNOWN.wireValue),
            ),
            stagedOutputs = root.optJSONArray("staged_outputs").let { array ->
                if (array == null) emptyList() else (0 until array.length()).mapNotNull { index ->
                    TaskOutput.fromJson(array.opt(index))
                }
            },
        )
    }
}

data class TaskRecord(
    val id: String,
    val createdAt: Long,
    val status: TaskStatus,
    val stage: String,
    val progress: Int,
    val title: String,
    val outputs: List<TaskOutput>,
    val error: String,
    val fileState: FileState,
    val platform: SourcePlatform = SourcePlatform.DOUYIN,
    val author: String = "",
    val authorAccountId: String = "",
) {
    val outputUris: List<String> get() = outputs.map(TaskOutput::uri)
}

fun taskFolderName(createdAt: Long, taskId: String): String =
    "${legacyTaskFolderName(createdAt)}_${taskId.take(8)}"

fun redownloadTaskFolderName(createdAt: Long, taskId: String): String =
    "${taskFolderName(createdAt, taskId)}_r${(createdAt % 1000).toString().padStart(3, '0')}"

fun legacyTaskFolderName(createdAt: Long): String =
    SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }.format(Date(createdAt))

private fun JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }
}

private fun JSONArray?.toStringLists(): List<List<String>> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optJSONArray(index)?.toStrings()?.takeIf(List<String>::isNotEmpty)
    }
}

private fun <T> JSONArray?.toObjects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(block) }
}
