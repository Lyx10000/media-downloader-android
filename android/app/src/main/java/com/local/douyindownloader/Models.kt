package com.local.douyindownloader

import org.json.JSONArray
import org.json.JSONObject

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

data class ParseResult(
    val ok: Boolean,
    val awemeId: String = "",
    val kind: String = "video",
    val author: String = "",
    val description: String = "",
    val coverUrl: String = "",
    val variants: List<MediaVariant> = emptyList(),
    val audioUrls: List<String> = emptyList(),
    val imageUrls: List<String> = emptyList(),
    val musicUrls: List<String> = emptyList(),
    val responseShape: String = "{}",
    val errorCode: String = "",
    val message: String = "",
    val rawJson: String = "{}",
) {
    companion object {
        fun fromJson(text: String): ParseResult {
            val root = JSONObject(text)
            if (!root.optBoolean("ok")) {
                return ParseResult(
                    ok = false,
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
            return ParseResult(
                ok = true,
                awemeId = root.optString("aweme_id"),
                kind = root.optString("kind", "video"),
                author = root.optString("author"),
                description = root.optString("description"),
                coverUrl = root.optString("cover_url"),
                variants = variants,
                audioUrls = root.optJSONArray("audio_urls").toStrings(),
                imageUrls = root.optJSONArray("image_urls").toStrings(),
                musicUrls = root.optJSONArray("music_urls").toStrings(),
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
    val mode: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("task_id", taskId)
        put("created_at", createdAt)
        put("result", JSONObject(result.rawJson))
        put("variant_index", variantIndex)
        put("mode", mode)
    }.toString()

    companion object {
        fun fromJson(text: String): TaskSpec {
            val root = JSONObject(text)
            val resultJson = root.getJSONObject("result").toString()
            return TaskSpec(
                taskId = root.getString("task_id"),
                createdAt = root.getLong("created_at"),
                result = ParseResult.fromJson(resultJson),
                variantIndex = root.optInt("variant_index", 0),
                mode = root.optString("mode", "merge_keep"),
            )
        }
    }
}

data class TaskRecord(
    val id: String,
    val createdAt: Long,
    val status: String,
    val stage: String,
    val progress: Int,
    val title: String,
    val outputUris: List<String>,
    val error: String,
)

private fun JSONArray?.toStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }
}

private fun <T> JSONArray?.toObjects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(block) }
}
