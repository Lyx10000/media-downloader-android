package com.local.multiplatformdownloader.core.settings

import com.local.multiplatformdownloader.core.model.DownloadMode

import org.json.JSONObject

enum class BatchVideoQuality(val wireValue: String, val maxHeight: Int?) {
    HIGHEST("highest", null),
    UP_TO_1080P("up_to_1080p", 1080),
    UP_TO_720P("up_to_720p", 720);

    companion object {
        fun fromWire(value: String): BatchVideoQuality = entries.firstOrNull {
            it.wireValue == value
        } ?: HIGHEST
    }
}

/** Persisted defaults shared by creator downloads and task redownloads. */
data class BatchDownloadSettings(
    val quality: BatchVideoQuality = BatchVideoQuality.HIGHEST,
    val mode: DownloadMode = DownloadMode.MERGE_KEEP,
    val preferH264: Boolean = false,
    val bilibiliAllParts: Boolean = true,
) {
    fun toJson(): String = JSONObject().apply {
        put("quality", quality.wireValue)
        put("mode", mode.wireValue)
        put("prefer_h264", preferH264)
        put("bilibili_all_parts", bilibiliAllParts)
    }.toString()

    companion object {
        fun fromJson(value: String): BatchDownloadSettings = runCatching {
            val root = JSONObject(value)
            BatchDownloadSettings(
                quality = BatchVideoQuality.fromWire(root.optString("quality")),
                mode = DownloadMode.fromWire(root.optString("mode")),
                preferH264 = root.optBoolean("prefer_h264"),
                bilibiliAllParts = root.optBoolean("bilibili_all_parts", true),
            )
        }.getOrDefault(BatchDownloadSettings())
    }
}
