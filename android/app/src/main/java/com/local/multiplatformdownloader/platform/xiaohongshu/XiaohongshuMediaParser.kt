package com.local.multiplatformdownloader.platform.xiaohongshu


import com.local.multiplatformdownloader.core.model.LivePhotoPair
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.network.firstArray
import com.local.multiplatformdownloader.core.network.firstObject
import com.local.multiplatformdownloader.core.network.firstString
import com.local.multiplatformdownloader.core.network.firstValue
import com.local.multiplatformdownloader.core.network.jsonLong
import com.local.multiplatformdownloader.core.network.jsonNumber
import com.local.multiplatformdownloader.core.network.keysInOrder
import com.local.multiplatformdownloader.core.network.responseShape
import com.local.multiplatformdownloader.core.network.stableDistinct
import com.local.multiplatformdownloader.core.network.values
import com.local.multiplatformdownloader.platform.common.PlatformParseException

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

internal object XiaohongshuMediaParser {
    data class TargetNoteMatch(val note: JSONObject, val strategy: String)

    const val HOME_URL = "https://www.xiaohongshu.com/"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

    private val shareUrlPattern = Regex(
        "https?://(?:[a-z0-9-]+\\.)*(?:xiaohongshu\\.com|xhslink\\.(?:cn|com))" +
            "[^\\s，。；：！？）】》]*",
        RegexOption.IGNORE_CASE,
    )
    private val noteIdPatterns = listOf(
        Regex(
            "/(?:explore|discovery/item|note)/([0-9a-zA-Z_-]+)",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "/user/profile/[0-9a-zA-Z_-]+/([0-9a-zA-Z_-]+)",
            RegexOption.IGNORE_CASE,
        ),
    )
    private val imageCdns = listOf(
        "https://sns-img-bd.xhscdn.com",
        "https://sns-img-hw.xhscdn.com",
        "https://sns-img-qc.xhscdn.com",
        "https://sns-img-qn.xhscdn.com",
    )

    fun isShare(value: String): Boolean = shareUrlPattern.containsMatchIn(value)

    fun extractShareUrl(value: String): String = shareUrlPattern.find(value)?.value
        ?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '。', '，', '；', '：', '！', '？', '》', '】')
        ?: throw PlatformParseException("UNSUPPORTED_URL", "没有找到小红书链接")

    fun redirectTarget(url: String): String {
        val uri = parseUri(url) ?: return ""
        val redirectPath = parseQuery(uri.rawQuery)["redirectPath"]?.firstOrNull().orEmpty()
        if (redirectPath.isBlank()) return ""
        val target = runCatching { uri.resolve(redirectPath).toString() }.getOrDefault("")
        return target.takeIf(::isXiaohongshuPage).orEmpty()
    }

    fun noteIdFromUrl(url: String): String = noteIdPatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(url)?.groupValues?.get(1)
    }.orEmpty()

    fun extractInitialState(pageHtml: String): JSONObject? {
        val text = decodeHtmlEntities(pageHtml)
        for (marker in listOf("window.__INITIAL_STATE__", "__INITIAL_STATE__")) {
            var searchFrom = 0
            while (searchFrom < text.length) {
                val markerIndex = text.indexOf(marker, searchFrom)
                if (markerIndex < 0) break
                val objectStart = text.indexOf('{', markerIndex + marker.length)
                if (objectStart < 0) break
                val payload = extractBalancedObject(text, objectStart)
                if (payload.isNotBlank()) {
                    runCatching { JSONObject(replaceUndefined(payload)) }.getOrNull()?.let { return it }
                }
                searchFrom = markerIndex + marker.length
            }
        }
        return null
    }

    fun parseStatePayload(payload: String): JSONObject? {
        val trimmed = payload.trim().removeSuffix(";").trim()
        if (trimmed.isBlank()) return null
        return runCatching { JSONObject(replaceUndefined(trimmed)) }.getOrNull()
            ?: extractInitialState(payload)
    }

    fun findTargetNote(state: JSONObject, targetNoteId: String): JSONObject? =
        findTargetNoteWithStrategy(state, targetNoteId)?.note

    fun findTargetNoteWithStrategy(state: JSONObject, targetNoteId: String): TargetNoteMatch? {
        if (targetNoteId.isBlank()) return null
        val stateRoot = unwrap(state, unwrapNote = false)
        val noteRoot = unwrap(stateRoot.firstObject("note") ?: JSONObject(), unwrapNote = false)
        val noteMap = noteRoot.firstObject("noteDetailMap", "note_detail_map")
            ?.let { unwrap(it, unwrapNote = false) }
        if (noteMap != null) {
            noteMap.optJSONObject(targetNoteId)?.let(::unwrap)?.let { direct ->
                val embeddedId = noteId(direct)
                if (embeddedId.isBlank() || embeddedId == targetNoteId) {
                    return TargetNoteMatch(direct, "known_state_path")
                }
            }
            noteMap.keysInOrder().forEach { key ->
                val candidate = noteMap.optJSONObject(key)?.let(::unwrap)
                if (candidate != null && noteId(candidate) == targetNoteId) {
                    return TargetNoteMatch(candidate, "known_state_path")
                }
            }
        }
        val candidate = noteRoot.firstObject("note", "noteDetail", "note_detail")?.let(::unwrap)
        candidate?.takeIf { noteId(it) == targetNoteId }?.let {
            return TargetNoteMatch(it, "known_state_path")
        }
        return findTargetNoteDeep(stateRoot, targetNoteId)?.let {
            TargetNoteMatch(it, "bounded_deep_scan")
        }
    }

    fun imageCandidates(value: Any?): List<String> {
        val image = when (value) {
            is String -> JSONObject().put("url", value)
            is JSONObject -> value
            else -> return emptyList()
        }
        val explicitOriginals = listOf(
            "original", "originalUrl", "original_url", "urlOriginal", "url_original",
        ).flatMap { asUrls(image.opt(it)) }
        val defaults = listOf(
            "urlDefault", "url_default", "url", "downloadUrl", "download_url",
        ).flatMap { asUrls(image.opt(it)) }
        val previews = listOf(
            "urlPre", "url_pre", "preview", "urlList", "url_list", "infoList",
        ).flatMap { asUrls(image.opt(it)) }
        val traceCandidates = image.firstString("traceId", "trace_id")
            .trimStart('/')
            .takeIf(String::isNotBlank)
            ?.let { traceId -> imageCdns.map { cdn -> "$cdn/$traceId" } }
            .orEmpty()
        val restored = (explicitOriginals + defaults + previews).flatMap { source ->
            originalObjectPath(source).takeIf(String::isNotBlank)?.let { path ->
                imageCdns.map { cdn -> "$cdn/$path" }
            }.orEmpty()
        }
        return stableDistinct(explicitOriginals + traceCandidates + restored + defaults + previews)
    }

    /**
     * Covers and avatars should prefer the URL the page actually rendered. Reconstructed
     * origin candidates are valuable for downloads, but are not guaranteed to be valid for
     * every thumbnail path returned by sns-webpic.
     */
    fun previewCandidates(value: Any?): List<String> {
        val image = when (value) {
            is String -> JSONObject().put("url", value)
            is JSONObject -> value
            else -> return emptyList()
        }
        val rendered = listOf(
            "urlDefault", "url_default", "url", "urlPre", "url_pre", "preview",
            "original", "originalUrl", "original_url", "urlOriginal", "url_original",
            "urlList", "url_list", "infoList",
        ).flatMap { asUrls(image.opt(it)) }
        return stableDistinct(rendered + imageCandidates(image))
    }

    fun extractImageCandidates(note: JSONObject): List<List<String>> {
        val images = note.firstArray("imageList", "image_list", "imagesList", "images_list")
            ?: return emptyList()
        return images.values().mapNotNull { imageCandidates(it).takeIf(List<String>::isNotEmpty) }
    }

    fun extractVideoVariants(note: JSONObject): List<MediaVariant> {
        val video = note.firstObject("video", "videoInfo", "video_info") ?: JSONObject()
        val media = video.firstObject("media") ?: JSONObject()
        val streamRoot = media.firstObject("stream") ?: video.firstObject("stream") ?: JSONObject()
        val variants = ArrayList<XhsVariant>()
        streamRoot.keysInOrder().forEach { codec ->
            variants += streamItems(streamRoot.optJSONArray(codec), codec)
        }

        val consumer = video.firstObject("consumer") ?: JSONObject()
        val originKey = consumer.firstString("originVideoKey", "origin_video_key")
            .ifBlank { video.firstString("originVideoKey", "origin_video_key") }
        if (originKey.isNotBlank()) {
            val originUrl = secureCdnUrl(
                if (originKey.startsWith("http")) originKey
                else "https://sns-video-bd.xhscdn.com/${originKey.trimStart('/')}",
            )
            if (isDirectVideoUrl(originUrl)) {
                variants += XhsVariant(originUrl, listOf(originUrl), 0, 0, 0, 0, 0, "未知", "origin")
            }
        }
        if (variants.isEmpty()) {
            stableDistinct(recursiveVideoUrls(video)).forEach { url ->
                variants += XhsVariant(url, listOf(url), 0, 0, 0, 0, 0, "未知", "fallback")
            }
        }

        val unique = LinkedHashMap<String, XhsVariant>()
        variants.forEach { variant ->
            val previous = unique[variant.url]
            if (previous == null) unique[variant.url] = variant
            else if (variant.urls.size > previous.urls.size) unique[variant.url] = previous.copy(urls = variant.urls)
        }
        return unique.values.sortedWith(
            compareByDescending<XhsVariant> { it.width.toLong() * it.height }
                .thenByDescending(XhsVariant::fps)
                .thenByDescending(XhsVariant::bitrate)
                .thenByDescending(XhsVariant::size)
                .thenByDescending { it.codec == "H.264" }
                .thenByDescending { it.source == "stream" },
        ).map { item ->
            MediaVariant(
                width = item.width,
                height = item.height,
                bitrate = item.bitrate,
                fps = item.fps,
                codec = item.codec,
                size = item.size,
                sizeSource = if (item.size > 0) "api" else "unknown",
                urls = item.urls,
            )
        }
    }

    fun normalizeNote(note: JSONObject, noteId: String, canonicalUrl: String): ParseResult {
        val user = note.firstObject("user", "author") ?: JSONObject()
        val images = extractImageCandidates(note)
        val imageObjects = note.firstArray("imageList", "image_list", "imagesList", "images_list")
            ?.values()
            .orEmpty()
            .mapNotNull { it as? JSONObject }
        val livePhotos = imageObjects.mapNotNull { image ->
            val candidates = imageCandidates(image)
            val imageIndex = images.indexOfFirst { it == candidates }
                .takeIf { it >= 0 }
                ?: return@mapNotNull null
            val variants = extractImageMotionVariants(image)
            variants.takeIf(List<MediaVariant>::isNotEmpty)?.let {
                LivePhotoPair(imageIndex, candidates, it)
            }
        }.distinctBy(LivePhotoPair::imageIndex)
        val variants = extractVideoVariants(note)
        val noteType = note.firstString("type", "noteType", "note_type").lowercase()
        val isVideo = noteType == "video" || variants.isNotEmpty()
        if (!isVideo && images.isEmpty()) {
            throw PlatformParseException("MEDIA_EMPTY", "笔记中没有找到可下载的图片或视频")
        }
        if (isVideo && variants.isEmpty()) {
            throw PlatformParseException("MEDIA_EMPTY", "当前笔记没有可直接下载的视频档位，可能只提供了 HLS 流")
        }
        val cover = imageObjects.firstOrNull()
            ?.let(::previewCandidates)
            ?.firstOrNull()
            .orEmpty()
            .ifBlank {
                previewCandidates(note.firstObject("cover")).firstOrNull().orEmpty()
            }
        return ParseResult(
            ok = true,
            platform = SourcePlatform.XIAOHONGSHU,
            contentId = noteId,
            canonicalUrl = canonicalUrl,
            referer = HOME_URL,
            kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
            author = user.firstString("nickname", "nickName", "nick_name", "name"),
            authorAccountId = user.firstString("redId", "red_id"),
            authorStableId = user.firstString("userId", "user_id", "id"),
            authorProfileUrl = authorProfileUrl(note, canonicalUrl),
            authorAvatarUrl = previewCandidates(
                user.firstValue("avatar", "image", "avatarUrl", "avatar_url", "imageb", "images"),
            ).firstOrNull().orEmpty(),
            description = note.firstString("title", "displayTitle", "display_title")
                .ifBlank { note.firstString("desc", "description") },
            coverUrl = cover,
            variants = variants,
            imageUrls = images.mapNotNull(List<String>::firstOrNull),
            imageCandidates = images,
            livePhotos = livePhotos,
            responseShape = (responseShape(note) as JSONObject).toString(),
        )
    }

    private fun extractImageMotionVariants(image: JSONObject): List<MediaVariant> {
        image.firstObject("video", "dynamicVideo", "dynamic_video")?.let { video ->
            extractVideoVariants(JSONObject().put("video", video)).takeIf(List<MediaVariant>::isNotEmpty)
                ?.let { return it }
        }
        val stream = image.firstObject("stream") ?: return emptyList()
        return extractVideoVariants(
            JSONObject().put(
                "video",
                JSONObject().put("media", JSONObject().put("stream", stream)),
            ),
        )
    }

    fun authorProfileUrl(note: JSONObject, canonicalUrl: String): String {
        val user = note.firstObject("user", "author") ?: return ""
        val userId = user.firstString("userId", "user_id", "id")
        if (userId.isBlank()) return ""
        val token = parseUri(canonicalUrl)?.rawQuery
            ?.let(::parseQuery)
            ?.get("xsec_token")
            ?.firstOrNull()
            .orEmpty()
        val base = "${HOME_URL}user/profile/${encodeQuery(userId)}"
        return if (token.isBlank()) base else {
            "$base?xsec_token=${encodeQuery(token)}&xsec_source=pc_note"
        }
    }

    fun profilePublicAccountId(pageHtml: String): String {
        val state = extractInitialState(pageHtml) ?: return ""
        val root = unwrap(state, unwrapNote = false)
        val user = root.firstObject("user")?.let { unwrap(it, unwrapNote = false) } ?: return ""
        val pageData = user.firstObject("userPageData", "user_page_data")
            ?.let { unwrap(it, unwrapNote = false) }
            ?: return ""
        val basic = pageData.firstObject("basicInfo", "basic_info")
            ?.let { unwrap(it, unwrapNote = false) }
            ?: return ""
        return basic.firstString("redId", "red_id")
    }

    fun secureCdnUrl(url: String): String {
        val uri = parseUri(url) ?: return url
        val host = uri.host?.lowercase().orEmpty()
        return if (uri.scheme.equals("http", ignoreCase = true) && host.endsWith(".xhscdn.com")) {
            URI("https", uri.rawUserInfo, uri.host, uri.port, uri.rawPath, uri.rawQuery, uri.rawFragment).toString()
        } else url
    }

    fun isDirectVideoUrl(url: String): Boolean {
        val uri = parseUri(url) ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val text = url.lowercase()
        val segmented = Regex(
            "m3u8|/hls(?:[/?#]|$)|[?&](?:format|type|protocol)=hls(?:[&#]|$)|" +
                "\\.(?:ts|m2ts)(?:[?#]|$)",
        ).containsMatchIn(text)
        return host.endsWith("xhscdn.com") && !segmented &&
            ("sns-video" in host || ".mp4" in uri.path.orEmpty().lowercase())
    }

    private fun streamItems(stream: JSONArray?, codec: String): List<XhsVariant> =
        stream?.values().orEmpty().mapNotNull { value ->
            val item = value as? JSONObject ?: return@mapNotNull null
            val urls = stableDistinct(asUrls(item).filter(::isDirectVideoUrl))
            val url = urls.firstOrNull() ?: return@mapNotNull null
            XhsVariant(
                url = url,
                urls = urls,
                width = item.firstValue("width", "videoWidth", "video_width").jsonNumber(),
                height = item.firstValue("height", "videoHeight", "video_height").jsonNumber(),
                fps = item.firstValue("fps", "frameRate", "frame_rate").jsonNumber(),
                bitrate = item.firstValue("avgBitrate", "avg_bitrate", "bitrate", "videoBitrate").jsonNumber(),
                size = item.firstValue("size", "fileSize", "file_size").jsonLong(),
                codec = codec.uppercase().replace("H264", "H.264").replace("H265", "H.265"),
                source = "stream",
            )
        }

    private fun recursiveVideoUrls(value: Any?, depth: Int = 0): List<String> {
        if (depth > 10) return emptyList()
        return when (value) {
            is String -> if (isDirectVideoUrl(value)) listOf(secureCdnUrl(value)) else emptyList()
            is JSONArray -> value.values().flatMap { recursiveVideoUrls(it, depth + 1) }
            is JSONObject -> value.keysInOrder().flatMap { recursiveVideoUrls(value.opt(it), depth + 1) }
            else -> emptyList()
        }
    }

    private fun asUrls(value: Any?): List<String> = when (value) {
        is String -> if (value.startsWith("http://") || value.startsWith("https://")) {
            listOf(secureCdnUrl(value))
        } else emptyList()
        is JSONArray -> value.values().flatMap(::asUrls)
        is JSONObject -> listOf(
            "url", "masterUrl", "master_url", "downloadUrl", "download_url",
            "backupUrls", "backup_urls", "urlList", "url_list",
        ).flatMap { asUrls(value.opt(it)) }
        else -> emptyList()
    }

    private fun originalObjectPath(url: String): String {
        val uri = parseUri(url) ?: return ""
        if (!uri.host.orEmpty().lowercase().endsWith("xhscdn.com")) return ""
        val path = decodePercent(uri.rawPath.orEmpty()).trimStart('/').substringBefore('!')
        if (path.isBlank()) return ""
        for (marker in listOf("spectrum/", "notes_pre_post/")) {
            val index = path.indexOf(marker)
            if (index >= 0) return path.substring(index)
        }
        return path.substringAfterLast('/')
    }

    private fun noteId(note: JSONObject): String =
        note.firstValue("noteId", "note_id", "id", "note_id_str")?.toString().orEmpty()

    private fun findTargetNoteDeep(root: JSONObject, targetNoteId: String): JSONObject? {
        val queue = ArrayDeque<ScanNode>()
        queue.add(ScanNode(root, 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val (value, depth) = queue.removeFirst()
            visited += 1
            when (value) {
                is JSONObject -> {
                    val candidate = unwrap(value)
                    if (noteId(candidate) == targetNoteId && isLikelyNote(candidate)) return candidate
                    if (depth < MAX_SCAN_DEPTH) {
                        value.keysInOrder().forEach { key ->
                            when (val child = value.opt(key)) {
                                is JSONObject, is JSONArray -> queue.add(ScanNode(child, depth + 1))
                            }
                        }
                    }
                }
                is JSONArray -> if (depth < MAX_SCAN_DEPTH) {
                    value.values().forEach { child ->
                        if (child is JSONObject || child is JSONArray) {
                            queue.add(ScanNode(child, depth + 1))
                        }
                    }
                }
            }
        }
        return null
    }

    private fun isLikelyNote(value: JSONObject): Boolean =
        value.firstArray("imageList", "image_list", "images") != null ||
            value.firstObject("video", "videoInfo", "video_info") != null

    private fun unwrap(source: JSONObject, unwrapNote: Boolean = true): JSONObject {
        var current = source
        repeat(4) {
            current = when {
                unwrapNote && current.optJSONObject("note") != null -> current.getJSONObject("note")
                current.optJSONObject("_value") != null -> current.getJSONObject("_value")
                current.optJSONObject("value") != null && current.length() <= 3 -> current.getJSONObject("value")
                else -> return current
            }
        }
        return current
    }

    private data class ScanNode(val value: Any, val depth: Int)

    private fun extractBalancedObject(text: String, start: Int): String {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
                continue
            }
            when (character) {
                '\'', '"' -> quote = character
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return ""
    }

    private fun replaceUndefined(text: String): String {
        val result = StringBuilder(text.length)
        var index = 0
        var quote: Char? = null
        var escaped = false
        while (index < text.length) {
            val character = text[index]
            if (quote != null) {
                result.append(character)
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
                index += 1
                continue
            }
            if (character == '\'' || character == '"') {
                quote = character
                result.append(character)
                index += 1
                continue
            }
            if (text.startsWith("undefined", index)) {
                val before = text.getOrNull(index - 1)
                val after = text.getOrNull(index + 9)
                val partOfName = before?.let(::isJavaScriptNameCharacter) == true ||
                    after?.let(::isJavaScriptNameCharacter) == true
                if (!partOfName) {
                    result.append("null")
                    index += 9
                    continue
                }
            }
            result.append(character)
            index += 1
        }
        return result.toString()
    }

    private const val MAX_SCAN_NODES = 50_000
    private const val MAX_SCAN_DEPTH = 12

    private fun isJavaScriptNameCharacter(value: Char): Boolean = value.isLetterOrDigit() || value in "_$"

    private fun decodeHtmlEntities(text: String): String = HTML_ENTITY.replace(text) { match ->
        val token = match.groupValues[1]
        when {
            token.startsWith("#x", ignoreCase = true) -> token.drop(2).toIntOrNull(16)?.toChar()?.toString()
            token.startsWith('#') -> token.drop(1).toIntOrNull()?.toChar()?.toString()
            else -> NAMED_ENTITIES[token.lowercase()]
        } ?: match.value
    }

    private fun parseQuery(query: String?): Map<String, List<String>> {
        if (query.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, MutableList<String>>()
        query.split('&').forEach { part ->
            val (name, value) = part.split('=', limit = 2).let { pieces ->
                decodeQuery(pieces[0]) to decodeQuery(pieces.getOrElse(1) { "" })
            }
            result.getOrPut(name) { mutableListOf() } += value
        }
        return result
    }

    private fun decodeQuery(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decodePercent(value: String): String = decodeQuery(value.replace("+", "%2B"))

    private fun isXiaohongshuPage(url: String): Boolean {
        val host = parseUri(url)?.host?.lowercase().orEmpty()
        return host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com")
    }

    private fun parseUri(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private data class XhsVariant(
        val url: String,
        val urls: List<String>,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val size: Long,
        val codec: String,
        val source: String,
    )

    private val HTML_ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0",
    )
}
