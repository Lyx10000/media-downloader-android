package com.local.douyindownloader

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object DouyinMediaNormalizer {
    fun normalize(detail: JSONObject, itemId: String, itemKind: MediaKind): ParseResult {
        val author = detail.firstObject("author") ?: JSONObject()
        val contentId = detail.firstString("aweme_id", "awemeId").ifBlank { itemId }
        val images = detail.firstArray("images") ?: JSONArray()
        val kind = if (images.length() > 0) MediaKind.IMAGE else itemKind
        val imageCandidates = images.values().mapNotNull { image ->
            (image as? JSONObject)?.let(::extractImageUrls)?.takeIf(List<String>::isNotEmpty)
        }
        val video = detail.firstObject("video") ?: JSONObject()
        val variants = if (kind == MediaKind.IMAGE) emptyList() else {
            extractVideoVariants(video, detail.firstValue("duration").jsonNumber())
        }
        val audioUrls = if (kind == MediaKind.IMAGE) emptyList() else extractAudioUrls(video)
        val coverUrl = if (kind == MediaKind.IMAGE) {
            imageCandidates.firstOrNull()?.firstOrNull().orEmpty()
        } else {
            addressUrls(video.firstValue("cover", "origin_cover", "originCover")).firstOrNull().orEmpty()
        }
        val music = detail.firstObject("music") ?: JSONObject()
        val musicUrls = addressUrls(music.firstValue("play_url", "playUrl", "play_addr"))
        return ParseResult(
            ok = true,
            platform = SourcePlatform.DOUYIN,
            contentId = contentId,
            canonicalUrl = "https://www.douyin.com/${if (kind == MediaKind.IMAGE) "note" else "video"}/$contentId",
            referer = SourcePlatform.DOUYIN.referer,
            kind = kind,
            author = author.firstString("nickname", "name"),
            authorAccountId = author.firstString("unique_id", "uniqueId")
                .ifBlank { author.firstString("short_id", "shortId") }
                .takeUnless { it == "0" }
                .orEmpty(),
            description = detail.firstString("desc", "description"),
            coverUrl = coverUrl,
            variants = variants,
            audioUrls = audioUrls,
            imageUrls = imageCandidates.mapNotNull(List<String>::firstOrNull),
            imageCandidates = imageCandidates,
            musicUrls = musicUrls,
            responseShape = (responseShape(detail) as JSONObject).toString(),
        )
    }

    fun addressUrls(value: Any?): List<String> {
        val result = ArrayList<String>()
        fun visit(current: Any?) {
            when (current) {
                is String -> if (current.startsWith("http")) result += current
                is JSONArray -> current.values().forEach(::visit)
                is JSONObject -> listOf(
                    "url_list", "urlList", "main_url", "backup_url", "fallback_url", "src", "url",
                ).forEach { key -> if (current.has(key)) visit(current.opt(key)) }
            }
        }
        visit(value)
        return stableDistinct(result)
    }

    fun extractAudioUrls(video: JSONObject): List<String> {
        val rates = video.firstArray("bit_rate_audio", "bitRateAudio") ?: return emptyList()
        return rates.values().mapNotNull { value ->
            val rate = value as? JSONObject ?: return@mapNotNull null
            val meta = rate.firstObject("audio_meta", "audioMeta") ?: rate
            val bitrate = (meta.firstValue("bitrate") ?: rate.firstValue("bit_rate")).jsonNumber()
            val urls = addressUrls(meta.firstValue("url_list", "urlList") ?: meta)
            urls.takeIf(List<String>::isNotEmpty)?.let { bitrate to it }
        }.maxByOrNull(Pair<Int, List<String>>::first)?.second.orEmpty()
    }

    fun extractVideoVariants(video: JSONObject, durationMs: Int = 0): List<MediaVariant> {
        val rates = video.firstArray("bit_rate", "bitRateList") ?: JSONArray()
        val defaultDuration = (
            video.firstValue("duration", "duration_ms", "durationMs") ?: durationMs
            ).jsonNumber()
        val grouped = LinkedHashMap<VariantKey, MediaVariant>()
        rates.values().forEach { value ->
            val rate = value as? JSONObject ?: return@forEach
            val play = rate.firstObject("play_addr", "playAddr") ?: JSONObject()
            val urls = addressUrls(play).filterNot { url ->
                "media-audio" in url || "ies-music" in url
            }
            if (urls.isEmpty()) return@forEach
            val width = (play.firstValue("width") ?: rate.firstValue("width") ?: video.firstValue("width")).jsonNumber()
            val height = (play.firstValue("height") ?: rate.firstValue("height") ?: video.firstValue("height")).jsonNumber()
            val bitrate = rate.firstValue("bit_rate", "bitRate").jsonNumber()
            val fps = rate.firstValue("FPS", "fps").jsonNumber()
            val codecValue = rate.firstString("codec_type", "codecType").lowercase()
            val codec = if (
                rate.optBoolean("is_h265") || rate.optBoolean("isH265") ||
                codecValue in setOf("h265", "hevc", "bytevc1")
            ) "H.265" else "H.264"
            val exactSize = (
                play.firstValue("data_size", "dataSize", "file_size", "fileSize")
                    ?: rate.firstValue("data_size", "dataSize", "file_size", "fileSize")
                ).jsonLong()
            val rateDuration = (play.firstValue("duration") ?: rate.firstValue("duration") ?: defaultDuration).jsonNumber()
            val estimatedSize = if (bitrate > 0 && rateDuration > 0) {
                bitrate.toLong() * rateDuration / 8_000L
            } else 0L
            val variant = MediaVariant(
                width = width,
                height = height,
                bitrate = bitrate,
                fps = fps,
                codec = codec,
                size = exactSize.takeIf { it > 0 } ?: estimatedSize,
                sizeSource = when {
                    exactSize > 0 -> "api"
                    estimatedSize > 0 -> "estimated"
                    else -> "unknown"
                },
                urls = urls,
            )
            val key = VariantKey(width, height, fps, codec)
            val previous = grouped[key]
            if (previous == null || variant.bitrate > previous.bitrate) grouped[key] = variant
        }
        if (grouped.isEmpty()) {
            val fallback = addressUrls(video.firstValue("play_addr", "playAddr")).filterNot { url ->
                "media-audio" in url || "ies-music" in url
            }
            if (fallback.isNotEmpty()) {
                grouped[VariantKey(video.optInt("width"), video.optInt("height"), 0, "未知")] =
                    MediaVariant(
                        width = video.optInt("width"),
                        height = video.optInt("height"),
                        bitrate = 0,
                        fps = 0,
                        codec = "未知",
                        size = 0,
                        sizeSource = "unknown",
                        urls = fallback,
                    )
            }
        }
        return grouped.values.sortedWith(
            compareByDescending<MediaVariant> { it.width.toLong() * it.height }
                .thenByDescending(MediaVariant::fps)
                .thenByDescending(MediaVariant::bitrate)
                .thenByDescending { it.codec == "H.265" },
        )
    }

    fun extractImageUrls(image: JSONObject): List<String> {
        val ordered = ArrayList<String>()
        val watermarked = ArrayList<String>()
        val sourceGroups = listOf(
            listOf("download_url_list", "downloadUrlList", "download_url", "downloadUrl"),
            listOf("origin_url", "originUrl"),
            listOf("url_list", "urlList"),
        )
        sourceGroups.forEach { keys ->
            val group = keys.flatMap { key -> addressUrls(image.opt(key)) }
                .sortedByDescending(::imageQuality)
            group.forEach { url ->
                (if (isWatermarkedImageUrl(url)) watermarked else ordered) += url
            }
        }
        addressUrls(image).forEach { url ->
            (if (isWatermarkedImageUrl(url)) watermarked else ordered) += url
        }
        return stableDistinct(ordered + watermarked)
    }

    private fun imageQuality(url: String): Int = IMAGE_QUALITY.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun isWatermarkedImageUrl(url: String): Boolean {
        val decoded = runCatching { URLDecoder.decode(url, StandardCharsets.UTF_8.name()) }
            .getOrDefault(url)
            .lowercase()
        return listOf("tplv-dy-water", "tplv-dy-wm", "/watermark/").any(decoded::contains)
    }

    private data class VariantKey(val width: Int, val height: Int, val fps: Int, val codec: String)

    private val IMAGE_QUALITY = Regex("(?:[:_-]|%3A)q(\\d+)(?:[._:?&]|%2F|$)", RegexOption.IGNORE_CASE)
}
