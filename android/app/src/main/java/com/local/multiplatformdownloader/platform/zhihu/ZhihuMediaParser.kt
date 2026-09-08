package com.local.multiplatformdownloader.platform.zhihu

import com.local.multiplatformdownloader.core.model.DocumentAsset
import com.local.multiplatformdownloader.core.model.DocumentAssetKind
import com.local.multiplatformdownloader.core.model.DocumentBlock
import com.local.multiplatformdownloader.core.model.DocumentBlockType
import com.local.multiplatformdownloader.core.model.DocumentContent
import com.local.multiplatformdownloader.core.model.DocumentType
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.network.firstString
import com.local.multiplatformdownloader.core.network.firstValue
import com.local.multiplatformdownloader.core.network.jsonEpochMillis
import com.local.multiplatformdownloader.core.network.jsonLong
import com.local.multiplatformdownloader.core.network.jsonNumber
import com.local.multiplatformdownloader.core.network.keysInOrder
import com.local.multiplatformdownloader.core.network.responseShape
import com.local.multiplatformdownloader.core.network.stableDistinct
import com.local.multiplatformdownloader.core.network.values
import com.local.multiplatformdownloader.platform.common.PlatformParseException

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

internal data class ParsedZhihuRichContent(
    val blocks: List<DocumentBlock>,
    val assets: List<DocumentAsset>,
)

internal object ZhihuRichContentParser {
    fun parse(html: String): ParsedZhihuRichContent {
        val document = Jsoup.parseBodyFragment(html)
        val blocks = mutableListOf<DocumentBlock>()
        val assets = mutableListOf<DocumentAsset>()
        val assetKeys = LinkedHashSet<String>()
        var imageIndex = 0
        var videoIndex = 0

        fun appendImage(element: Element) {
            val urls = imageUrls(element)
            val primary = urls.firstOrNull() ?: return
            if (!assetKeys.add("image:$primary")) return
            imageIndex += 1
            val id = "image-$imageIndex"
            assets += DocumentAsset(
                id = id,
                kind = DocumentAssetKind.IMAGE,
                candidateUrls = urls,
                alt = element.attr("alt").trim(),
            )
            blocks += DocumentBlock(DocumentBlockType.IMAGE, assetId = id)
        }

        fun appendVideo(element: Element) {
            val sourceId = element.attr("data-lens-id").ifBlank {
                element.attr("data-video-id")
            }.ifBlank {
                VIDEO_ID.find(element.attr("href"))?.groupValues?.get(1).orEmpty()
            }
            if (sourceId.isBlank() || !assetKeys.add("video:$sourceId")) return
            videoIndex += 1
            val id = "video-$videoIndex"
            val covers = element.select("img").flatMap(::imageUrls).distinct()
            assets += DocumentAsset(
                id = id,
                kind = DocumentAssetKind.VIDEO,
                sourceId = sourceId,
                coverUrls = covers,
                alt = element.attr("data-name").trim().ifBlank { "知乎视频" },
            )
            blocks += DocumentBlock(DocumentBlockType.VIDEO, assetId = id)
        }

        fun inlineMarkdown(node: Node): String = when (node) {
            is TextNode -> node.getWholeText()
            !is Element -> ""
            else -> {
                val children = { node.childNodes().joinToString("") { inlineMarkdown(it) } }
                when (node.normalName()) {
                    "script", "style", "noscript" -> ""
                    "iframe" -> safeHttpUrl(node.attr("src")).takeIf(String::isNotBlank)
                        ?.let { "[外部视频]($it)" }.orEmpty()
                    "br" -> "\n"
                    "strong", "b" -> children().trim().takeIf(String::isNotBlank)?.let { "**$it**" }.orEmpty()
                    "em", "i" -> children().trim().takeIf(String::isNotBlank)?.let { "*$it*" }.orEmpty()
                    "del", "s", "strike" -> children().trim().takeIf(String::isNotBlank)?.let { "~~$it~~" }.orEmpty()
                    "code" -> children().trim().takeIf(String::isNotBlank)?.let {
                        val fence = if ('`' in it) "``" else "`"
                        "$fence$it$fence"
                    }.orEmpty()
                    "a" -> {
                        val label = children().trim()
                        val href = safeHttpUrl(node.attr("href"))
                        if (href.isBlank()) label else "[${label.ifBlank { href }}]($href)"
                    }
                    else -> children()
                }
            }
        }

        fun appendFlow(element: Element, type: DocumentBlockType, level: Int = 0) {
            val buffer = StringBuilder()
            fun flush() {
                val value = normalizeMarkdownWhitespace(buffer.toString())
                buffer.clear()
                if (value.isNotBlank()) blocks += DocumentBlock(type, text = value, level = level)
            }
            fun walk(node: Node) {
                when {
                    node is Element && isVideoNode(node) -> {
                        flush()
                        appendVideo(node)
                    }
                    node is Element && node.normalName() == "img" -> {
                        flush()
                        appendImage(node)
                    }
                    node is TextNode -> buffer.append(node.getWholeText())
                    node is Element && node.normalName() in setOf("script", "style", "noscript") -> Unit
                    node is Element && node.normalName() == "iframe" -> buffer.append(inlineMarkdown(node))
                    node is Element && node.normalName() == "br" -> buffer.append('\n')
                    node is Element && node.normalName() in setOf("strong", "b", "em", "i", "del", "s", "strike", "code", "a") ->
                        buffer.append(inlineMarkdown(node))
                    else -> node.childNodes().forEach(::walk)
                }
            }
            element.childNodes().forEach(::walk)
            flush()
        }

        fun appendElement(element: Element) {
            when {
                isVideoNode(element) -> appendVideo(element)
                element.normalName() == "img" -> appendImage(element)
                element.normalName().matches(HEADING) -> appendFlow(
                    element,
                    DocumentBlockType.HEADING,
                    element.normalName().drop(1).toIntOrNull()?.coerceIn(1, 6) ?: 1,
                )
                element.normalName() == "blockquote" -> appendFlow(element, DocumentBlockType.BLOCKQUOTE)
                element.normalName() == "pre" -> {
                    val code = element.selectFirst("code") ?: element
                    val language = code.classNames().firstOrNull { it.startsWith("language-") }
                        ?.removePrefix("language-").orEmpty()
                    code.wholeText().trimEnd().takeIf(String::isNotBlank)?.let { text ->
                        blocks += DocumentBlock(DocumentBlockType.CODE, text = text, language = language)
                    }
                }
                element.normalName() in setOf("ul", "ol") -> {
                    val ordered = element.normalName() == "ol"
                    val lines = element.children().filter { it.normalName() == "li" }.mapIndexed { index, item ->
                        val marker = if (ordered) "${index + 1}." else "-"
                        "$marker ${normalizeMarkdownWhitespace(inlineMarkdown(item))}"
                    }.filterNot { it.endsWith(' ') }
                    if (lines.isNotEmpty()) {
                        blocks += DocumentBlock(DocumentBlockType.LIST, text = lines.joinToString("\n"))
                    }
                }
                element.normalName() in setOf("table", "math") || element.hasClass("ztext-math") -> {
                    val safe = Jsoup.clean(
                        element.outerHtml(),
                        Safelist.relaxed().addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td", "math"),
                    ).trim()
                    if (safe.isNotBlank()) blocks += DocumentBlock(DocumentBlockType.HTML, text = safe)
                }
                element.normalName() in setOf("p", "div", "figure", "figcaption", "section") ->
                    appendFlow(element, DocumentBlockType.PARAGRAPH)
                else -> element.children().forEach(::appendElement)
            }
        }

        document.body().children().forEach(::appendElement)
        return ParsedZhihuRichContent(blocks, assets)
    }

    private fun isVideoNode(element: Element): Boolean =
        element.hasClass("video-box") || element.hasAttr("data-lens-id") || element.hasAttr("data-video-id")

    private fun imageUrls(element: Element): List<String> = stableDistinct(
        listOf(
            element.attr("data-original"),
            element.attr("data-actualsrc"),
            element.attr("src"),
            element.attr("data-default-watermark-src"),
        ) + element.attr("srcset").split(',').map { it.trim().substringBefore(' ') },
    ).map(::safeHttpUrl).filter(String::isNotBlank)

    private fun safeHttpUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return ""
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return ""
        return if (uri.scheme.equals("http", ignoreCase = true) &&
            uri.host.lowercase().let { it == "zhimg.com" || it.endsWith(".zhimg.com") || it.endsWith(".vzuu.com") }
        ) {
            URI("https", uri.rawUserInfo, uri.host, uri.port, uri.rawPath, uri.rawQuery, uri.rawFragment).toString()
        } else {
            uri.toString()
        }
    }

    private fun normalizeMarkdownWhitespace(value: String): String = value
        .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()

    private val HEADING = Regex("h[1-6]")
    private val VIDEO_ID = Regex("/(?:video|zvideo)/(\\d+)")
}

internal object ZhihuMediaParser {
    fun normalizeStandaloneVideo(
        payload: JSONObject,
        videoId: String,
        canonicalUrl: String,
    ): ParseResult {
        val video = payload.optJSONObject("video") ?: payload
        val variants = videoVariants(video)
        if (variants.isEmpty()) {
            throw PlatformParseException("MEDIA_EMPTY", "知乎视频没有返回可下载档位")
        }
        val author = sequenceOf(
            payload.optJSONObject("author"),
            video.optJSONObject("author"),
            payload.optJSONObject("creator"),
            video.optJSONObject("creator"),
        ).filterNotNull().firstOrNull() ?: JSONObject()
        val authorName = author.firstString("name", "nickname").ifBlank {
            payload.firstString("author_name", "authorName", "creator_name", "creatorName")
        }
        return ParseResult(
            ok = true,
            platform = SourcePlatform.ZHIHU,
            contentId = videoId,
            canonicalUrl = canonicalUrl,
            referer = canonicalUrl,
            kind = MediaKind.VIDEO,
            author = authorName,
            authorStableId = author.firstString("url_token", "urlToken", "id"),
            authorProfileUrl = author.firstString("url_token", "urlToken")
                .takeIf(String::isNotBlank)?.let { "https://www.zhihu.com/people/$it" }.orEmpty(),
            authorAvatarUrl = author.firstString("avatar_url", "avatarUrl"),
            description = payload.firstString("title", "description", "excerpt"),
            publishedAt = payload.firstValue("created_time", "created", "published_time").jsonEpochMillis()
                .takeIf { it > 0L }
                ?: video.firstValue("created_time", "created", "published_time").jsonEpochMillis(),
            coverUrl = payload.firstString("image_url", "image_cover").ifBlank {
                video.firstString("thumbnail", "image_url")
            },
            variants = variants,
            responseShape = (responseShape(payload) as JSONObject).toString(),
        )
    }

    fun normalizeDocument(
        payload: JSONObject,
        source: ResolvedZhihuSource,
        videoResolver: (String) -> JSONObject? = { null },
    ): ParseResult {
        val type = when (source.type) {
            ZhihuContentType.QUESTION -> error("问题不能归一化为单篇文档")
            ZhihuContentType.ARTICLE -> DocumentType.ARTICLE
            ZhihuContentType.ANSWER -> DocumentType.ANSWER
            ZhihuContentType.PIN -> DocumentType.PIN
            ZhihuContentType.VIDEO -> error("独立视频不能归一化为文档")
        }
        if (isRestricted(payload)) {
            throw PlatformParseException("CONTENT_RESTRICTED", "当前知乎内容需要额外权限")
        }
        val parsed = if (type == DocumentType.PIN && payload.opt("content") is JSONArray) {
            parsePin(payload.getJSONArray("content"))
        } else {
            val html = payload.firstString("content", "content_html", "contentHtml", "detail")
            if (html.isBlank()) throw PlatformParseException("DETAIL_EMPTY", "知乎正文为空")
            ZhihuRichContentParser.parse(html)
        }
        val warnings = mutableListOf<String>()
        val hydratedAssets = parsed.assets.map { asset ->
            if (asset.kind != DocumentAssetKind.VIDEO || asset.sourceId.isBlank()) return@map asset
            val videoPayload = runCatching { videoResolver(asset.sourceId) }.getOrNull()
            val variants = videoPayload?.let(::videoVariants).orEmpty()
            if (variants.isEmpty()) warnings += "视频 ${asset.sourceId} 暂无可下载档位"
            asset.copy(variants = variants)
        }
        val question = payload.optJSONObject("question") ?: JSONObject()
        val title = question.firstString("title").ifBlank {
            payload.firstString("title", "excerpt_title").ifBlank { "知乎${type.wireValue}" }
        }
        val authorObject = payload.optJSONObject("author") ?: JSONObject()
        val author = authorObject.firstString("name", "nickname")
        val document = DocumentContent(
            type = type,
            title = title,
            author = author,
            sourceUrl = source.canonicalUrl,
            blocks = parsed.blocks,
            assets = hydratedAssets,
            warnings = warnings,
        )
        val cover = hydratedAssets.firstNotNullOfOrNull { asset ->
            asset.candidateUrls.firstOrNull() ?: asset.coverUrls.firstOrNull()
        }.orEmpty()
        return ParseResult(
            ok = true,
            platform = SourcePlatform.ZHIHU,
            contentId = source.contentId,
            canonicalUrl = source.canonicalUrl,
            referer = source.canonicalUrl,
            kind = MediaKind.DOCUMENT,
            author = author,
            authorStableId = authorObject.firstString("url_token", "urlToken", "id"),
            authorProfileUrl = authorObject.firstString("url_token", "urlToken")
                .takeIf(String::isNotBlank)?.let { "https://www.zhihu.com/people/$it" }.orEmpty(),
            authorAvatarUrl = authorObject.firstString("avatar_url", "avatarUrl"),
            description = title,
            publishedAt = payload.firstValue("created_time", "created", "published_time").jsonEpochMillis(),
            coverUrl = cover,
            document = document,
            responseShape = (responseShape(payload) as JSONObject).toString(),
        )
    }

    fun videoVariants(payload: JSONObject): List<MediaVariant> {
        val playlist = payload.optJSONObject("playlist")
            ?: payload.optJSONObject("video")?.optJSONObject("playlist")
            ?: payload.optJSONObject("video_play")?.optJSONObject("playlist")
            ?: return emptyList()
        val items = mutableListOf<JSONObject>()
        collectPlaylistItems(playlist, items)
        val grouped = LinkedHashMap<String, MediaVariant>()
        items.forEach { item ->
            val urls = urls(item.firstValue("play_url", "url", "playUrl"))
            if (urls.isEmpty()) return@forEach
            val width = item.firstValue("width").jsonNumber()
            val height = item.firstValue("height").jsonNumber()
            val fps = item.firstValue("fps", "frame_rate").jsonNumber()
            val rawBitrate = item.firstValue("bitrate", "avg_bitrate", "maxbitrate").jsonNumber()
            val bitrate = normalizeBitrate(rawBitrate)
            val size = item.firstValue("size", "file_size").jsonLong()
            val codecText = item.firstString("video_codec", "codec").lowercase() + " " + urls.first().lowercase()
            val codec = when {
                "hevc" in codecText || "h265" in codecText -> "H.265"
                "avc" in codecText || "h264" in codecText -> "H.264"
                else -> ""
            }
            val fallbackKey = runCatching { URI(urls.first()).path }.getOrDefault(urls.first())
            val key = if (listOf(width, height, fps, bitrate).any { it > 0 } || size > 0L) {
                "$width:$height:$fps:$bitrate:$size"
            } else {
                fallbackKey
            }
            val previous = grouped[key]
            grouped[key] = if (previous == null) {
                MediaVariant(width, height, bitrate, fps, codec, size, if (size > 0L) "api" else "unknown", urls)
            } else {
                previous.copy(urls = stableDistinct(previous.urls + urls))
            }
        }
        return grouped.values.sortedWith(
            compareByDescending<MediaVariant> { it.width.toLong() * it.height }
                .thenByDescending(MediaVariant::fps)
                .thenByDescending(MediaVariant::bitrate)
                .thenByDescending(MediaVariant::size),
        )
    }

    private fun parsePin(content: JSONArray): ParsedZhihuRichContent {
        val html = buildString {
            content.values().forEach { value ->
                val item = value as? JSONObject ?: return@forEach
                when (item.optString("type").lowercase()) {
                    "text" -> append("<p>").append(Element("span").text(item.firstString("content", "text"))).append("</p>")
                    "image" -> urls(item.firstValue("url", "image")).firstOrNull()?.let { url ->
                        append("<img src=\"").append(url.replace("\"", "&quot;")).append("\">")
                    }
                    "video" -> item.firstString("video_id", "id").takeIf(String::isNotBlank)?.let { id ->
                        append("<a class=\"video-box\" data-lens-id=\"").append(id).append("\"></a>")
                    }
                }
            }
        }
        return ZhihuRichContentParser.parse(html)
    }

    private fun isRestricted(payload: JSONObject): Boolean =
        payload.optBoolean("is_content_paid") || payload.optBoolean("is_paid") ||
            payload.optBoolean("is_private") || payload.optBoolean("is_deleted")

    private fun collectPlaylistItems(value: Any?, target: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 5) return
        when (value) {
            is JSONObject -> {
                if (urls(value.firstValue("play_url", "url", "playUrl")).isNotEmpty()) target += value
                else value.keysInOrder().forEach { collectPlaylistItems(value.opt(it), target, depth + 1) }
            }
            is JSONArray -> value.values().forEach { collectPlaylistItems(it, target, depth + 1) }
        }
    }

    private fun urls(value: Any?): List<String> = when (value) {
        is String -> listOf(value).filter { safeMediaUrl(it) }
        is JSONArray -> value.values().flatMap(::urls)
        is JSONObject -> listOf("url", "play_url", "playUrl").flatMap { urls(value.opt(it)) }
        else -> emptyList()
    }

    private fun safeMediaUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    private fun normalizeBitrate(value: Int): Int = when {
        value <= 0 -> 0
        value <= 10_000 -> (value.toLong() * 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        else -> value
    }
}

internal object ZhihuPageStateExtractor {
    fun findEntity(html: String, entityName: String, entityId: String): JSONObject? {
        val page = Jsoup.parse(html)
        val scripts = page.select("script#js-initialData, script#__NEXT_DATA__")
        scripts.forEach { script ->
            val root = runCatching { JSONObject(script.data().ifBlank { script.html() }) }.getOrNull()
                ?: return@forEach
            findEntity(root, entityName, entityId, 0)?.let { return it }
        }
        return null
    }

    fun findEntityFromJson(json: String, entityName: String, entityId: String): JSONObject? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return findEntity(root, entityName, entityId, 0)
    }

    private fun findEntity(value: Any?, entityName: String, entityId: String, depth: Int): JSONObject? {
        if (depth > 12) return null
        return when (value) {
            is JSONObject -> {
                value.optJSONObject(entityName)?.optJSONObject(entityId)?.let { return it }
                value.keysInOrder().firstNotNullOfOrNull { key ->
                    findEntity(value.opt(key), entityName, entityId, depth + 1)
                }
            }
            is JSONArray -> value.values().firstNotNullOfOrNull {
                findEntity(it, entityName, entityId, depth + 1)
            }
            else -> null
        }
    }
}
