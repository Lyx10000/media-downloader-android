package com.local.multiplatformdownloader.platform.instagram


import com.local.multiplatformdownloader.core.model.MediaAttachment
import com.local.multiplatformdownloader.core.model.MediaAttachmentKind
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.ParserAttempt
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.model.extractSupportedSource
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.platform.common.MediaSizeHydrator
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
import com.local.multiplatformdownloader.platform.common.PlatformParser
import com.local.multiplatformdownloader.platform.common.detectPlatformCredential
import com.local.multiplatformdownloader.core.network.firstObject
import com.local.multiplatformdownloader.core.network.firstString
import com.local.multiplatformdownloader.core.network.firstValue
import com.local.multiplatformdownloader.core.network.keysInOrder
import com.local.multiplatformdownloader.platform.common.parseFailure
import com.local.multiplatformdownloader.core.network.responseShape
import com.local.multiplatformdownloader.core.network.values
import com.local.multiplatformdownloader.platform.common.PlatformParseException

import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal data class InstagramSource(val shortcode: String, val canonicalUrl: String) {
    val mediaId: String
        get() = shortcode.fold(BigInteger.ZERO) { value, character ->
            value.shiftLeft(6).add(BigInteger.valueOf(ALPHABET.indexOf(character).toLong()))
        }.toString()

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun resolve(url: String): InstagramSource {
            if (SourcePlatform.fromUrl(url) != SourcePlatform.INSTAGRAM) {
                throw PlatformParseException("UNSUPPORTED_URL", "没有找到 Instagram 作品链接")
            }
            val path = URI(url).path.orEmpty()
            val match = Regex("^/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{1,28})(?:/|$)").find(path)
                ?: throw PlatformParseException(
                    "UNSUPPORTED_URL", "请复制 Instagram 单条帖子或 Reel 链接，暂不支持主页、Stories 和直播",
                )
            val code = match.groupValues[1]
            return InstagramSource(code, "https://www.instagram.com/p/$code/")
        }
    }
}

/** Only accept media belonging to the requested post; recommendation cards are not fallbacks. */
internal object InstagramMediaNormalizer {
    fun gatingError(value: Any?, source: InstagramSource, depth: Int = 0): PlatformParseException? {
        if (depth > 35) return null
        if (value is JSONObject) {
            if (value.firstString("code", "shortcode") == source.shortcode) {
                val gate = value.optJSONObject("gating_ruling")
                if (gate != null && !gate.isNull("gating_type") && gate.optInt("gating_type") != 0) {
                    val age = (gate.firstString("title") + gate.firstString("description"))
                        .contains("age", ignoreCase = true)
                    return PlatformParseException("LOGIN_REQUIRED", if (age) {
                        "该 Instagram 作品受年龄或账号设置限制，请登录有查看权限的账号后重试"
                    } else {
                        "Instagram 限制了该作品的访问，请登录有查看权限的账号后重试"
                    })
                }
            }
            for (key in value.keysInOrder()) gatingError(value.opt(key), source, depth + 1)?.let { return it }
        } else if (value is JSONArray) {
            for (index in 0 until value.length()) gatingError(value.opt(index), source, depth + 1)?.let { return it }
        }
        return null
    }

    fun findMedia(value: Any?, source: InstagramSource, depth: Int = 0): JSONObject? {
        if (depth > 35) return null
        when (value) {
            is JSONObject -> {
                val code = value.firstString("code", "shortcode")
                val id = value.firstValue("pk", "id")?.toString()?.substringBefore('_')
                if ((code == source.shortcode || id == source.mediaId) &&
                    listOf("video_versions", "video_url", "carousel_media", "edge_sidecar_to_children",
                        "image_versions2", "display_url", "is_video", "media_type").any(value::has)) {
                    return value
                }
                for (key in value.keysInOrder()) {
                    findMedia(value.opt(key), source, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                findMedia(value.opt(index), source, depth + 1)?.let { return it }
            }
        }
        return null
    }

    fun fromHtml(html: String, source: InstagramSource): JSONObject? {
        val document = Jsoup.parse(html)
        for (script in document.select("script[type=application/json], script[data-sjs]")) {
            val value = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            findMedia(value, source)?.let { return it }
        }
        return null
    }

    fun normalize(media: JSONObject, source: InstagramSource): ParseResult {
        val user = media.firstObject("user", "owner") ?: JSONObject()
        val username = user.firstString("username")
        val carousel = media.optJSONArray("carousel_media")?.values()?.filterIsInstance<JSONObject>()
            ?: media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")?.values()
                ?.filterIsInstance<JSONObject>()?.mapNotNull { it.optJSONObject("node") }
            ?: listOf(media)
        val attachments = carousel.mapIndexed { index, item -> attachment(item, index) }
        if (attachments.isEmpty()) throw PlatformParseException("MEDIA_EMPTY", "该 Instagram 作品没有可下载媒体")
        val videos = attachments.filter { it.kind == MediaAttachmentKind.VIDEO }
        val images = attachments.filter { it.kind == MediaAttachmentKind.IMAGE }
        val caption = media.optJSONObject("caption")?.firstString("text").orEmpty().ifBlank {
            media.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")
                ?.optJSONObject(0)?.optJSONObject("node")?.firstString("text").orEmpty()
        }
        return ParseResult(
            ok = true, platform = SourcePlatform.INSTAGRAM, contentId = source.shortcode,
            canonicalUrl = source.canonicalUrl, kind = if (videos.isEmpty()) MediaKind.IMAGE else MediaKind.VIDEO,
            author = user.firstString("full_name", "name").ifBlank { username },
            authorAccountId = username, authorStableId = user.firstValue("pk", "id")?.toString().orEmpty(),
            authorProfileUrl = username.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
                ?.let { "https://www.instagram.com/$it/" }.orEmpty(),
            authorAvatarUrl = user.firstString("profile_pic_url", "profile_pic_url_hd"),
            description = caption, coverUrl = attachments.first().coverUrl,
            variants = videos.firstOrNull()?.variants.orEmpty(),
            imageUrls = images.mapNotNull { it.imageCandidates.firstOrNull() },
            imageCandidates = images.map { it.imageCandidates }, attachments = attachments,
            responseShape = JSONObject().put("source", "instagram").put("attachments", attachments.size)
                .put("videos", videos.size).put("images", images.size).toString(),
        )
    }

    private fun attachment(item: JSONObject, index: Int): MediaAttachment {
        val images = item.optJSONObject("image_versions2")?.optJSONArray("candidates")?.values()
            ?.filterIsInstance<JSONObject>()?.sortedByDescending { it.optLong("width") * it.optLong("height") }
            ?.mapNotNull { httpUrl(it.firstString("url")) }.orEmpty().ifEmpty {
                listOfNotNull(httpUrl(item.firstString("display_url", "display_src", "thumbnail_src")))
            }.distinct()
        val versions = item.optJSONArray("video_versions")?.values()?.filterIsInstance<JSONObject>().orEmpty()
        val isVideo = item.optBoolean("is_video") || item.optInt("media_type") == 2 ||
            versions.isNotEmpty() || item.firstString("video_url").isNotBlank() ||
            item.firstString("video_dash_manifest").isNotBlank()
        val variants = versions.mapNotNull { version ->
            val url = httpUrl(version.firstString("url")) ?: return@mapNotNull null
            variant(url, version.optInt("width"), version.optInt("height"), version.optInt("bitrate"))
        }.ifEmpty {
            listOfNotNull(httpUrl(item.firstString("video_url"))?.let { url ->
                val dimensions = item.optJSONObject("dimensions") ?: item
                variant(url, dimensions.optInt("width"), dimensions.optInt("height"), 0)
            })
        }.distinctBy { it.urls }.sortedWith(compareByDescending<MediaVariant> {
            it.width.toLong() * it.height
        }.thenByDescending { it.bitrate })
        if (isVideo && variants.isEmpty()) throw PlatformParseException(
            "UNSUPPORTED_STREAM", "该 Instagram 视频没有提供可直接下载的 MP4，暂不支持仅 DASH/HLS 的作品",
        )
        if (!isVideo && images.isEmpty()) throw PlatformParseException("DETAIL_EMPTY", "Instagram 返回的附件没有媒体地址")
        return MediaAttachment(
            id = item.firstValue("pk", "id")?.toString() ?: "media_${index + 1}",
            index = index, kind = if (isVideo) MediaAttachmentKind.VIDEO else MediaAttachmentKind.IMAGE,
            coverUrl = images.firstOrNull().orEmpty(),
            imageCandidates = if (isVideo) emptyList() else images, variants = variants,
        )
    }

    private fun variant(url: String, width: Int, height: Int, bitrate: Int) = MediaVariant(
        width, height, bitrate, 0, "", 0L, "unknown", listOf(url),
    )

    private fun httpUrl(value: String): String? = value.takeIf {
        runCatching { URI(it).let { uri -> uri.scheme == "https" && !uri.host.isNullOrBlank() } }.getOrDefault(false) &&
            !it.substringBefore('?').endsWith(".m3u8") && !it.substringBefore('?').endsWith(".mpd")
    }
}

@Singleton
internal class InstagramPlatformParser @Inject constructor(private val http: ParserHttpClient) : PlatformParser {
    override val platform = SourcePlatform.INSTAGRAM

    override fun parse(shareText: String, cookieHeader: String, pageSnapshot: WebPageSnapshot?): ParseResult {
        val attempts = mutableListOf<ParserAttempt>()
        return try {
            val url = extractSupportedSource(shareText)?.takeIf { it.platform == platform }?.url
                ?: throw PlatformParseException("UNSUPPORTED_URL", "没有找到 Instagram 链接")
            val source = InstagramSource.resolve(url)
            fun finish(media: JSONObject, strategy: String, status: Int): ParseResult {
                val result = InstagramMediaNormalizer.normalize(media, source)
                attempts += ParserAttempt(strategy, true, status)
                val attachments = result.attachments.map { attachment ->
                    attachment.copy(variants = MediaSizeHydrator.hydrate(attachment.variants) { urls ->
                        urls.firstOrNull()?.let { mediaUrl ->
                            runCatching { http.probeContentLength(mediaUrl, headers(source.canonicalUrl)) }
                                .getOrDefault(0L)
                        } ?: 0L
                    })
                }
                return result.copy(attachments = attachments,
                    variants = attachments.firstOrNull { it.kind == MediaAttachmentKind.VIDEO }?.variants.orEmpty(),
                    parserAttempts = attempts.toList())
            }
            if (pageSnapshot != null && SourcePlatform.fromUrl(pageSnapshot.finalUrl) == platform) {
                val state = runCatching { JSONObject(pageSnapshot.initialData) }.getOrNull()
                val media = InstagramMediaNormalizer.findMedia(state, source)
                    ?: InstagramMediaNormalizer.fromHtml(pageSnapshot.contentHtml, source)
                if (media != null) return finish(media, "web_page_state", 200)
                attempts += ParserAttempt("web_page_state", false, errorCode = "DETAIL_EMPTY")
            }
            val page = http.get(url, headers(url), cookieHeader)
            checkStatus(page)
            InstagramMediaNormalizer.fromHtml(page.body, source)?.let { return finish(it, "page_state", page.statusCode) }
            attempts += ParserAttempt("page_state", false, page.statusCode, "DETAIL_EMPTY")
            if (detectPlatformCredential(platform, cookieHeader) == PlatformCredentialState.DETECTED) {
                val info = http.get("https://www.instagram.com/api/v1/media/${source.mediaId}/info/",
                    headers(source.canonicalUrl), cookieHeader)
                if (info.statusCode in setOf(401, 403, 429)) checkStatus(info)
                val media = runCatching { JSONObject(info.body) }.getOrNull()
                    ?.let { InstagramMediaNormalizer.findMedia(it, source) }
                if (media != null) return finish(media, "media_info", info.statusCode)
                attempts += ParserAttempt("media_info", false, info.statusCode, "DETAIL_EMPTY")
            }
            val lsd = Regex("\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"")
                .find(page.body)?.groupValues?.get(1).orEmpty()
            val queryHeaders = headers(source.canonicalUrl).toMutableMap().apply {
                // Full mobile/desktop Chrome strings currently receive an HTML error shell
                // from this endpoint. Keep WebView's real mobile layout separate from API negotiation.
                put("User-Agent", "Mozilla/5.0")
                put("Content-Type", "application/x-www-form-urlencoded")
                put("X-FB-Friendly-Name", "PolarisLoggedOutDesktopWWWPostRootContentQuery")
                if (lsd.isNotBlank()) put("X-FB-LSD", lsd)
                cookieHeader.split(';').map(String::trim).firstOrNull { it.startsWith("csrftoken=") }
                    ?.substringAfter('=')?.let { put("X-CSRFToken", it) }
            }
            val form = mapOf(
                "lsd" to lsd, "fb_api_caller_class" to "RelayModern",
                "fb_api_req_friendly_name" to "PolarisLoggedOutDesktopWWWPostRootContentQuery",
                "server_timestamps" to "true", "doc_id" to "27130156389949648",
                "variables" to JSONObject().put("media_id", source.mediaId).toString(),
            ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
            val response = http.post("https://www.instagram.com/api/graphql", queryHeaders, cookieHeader, form.toByteArray())
            checkStatus(response)
            val root = runCatching { JSONObject(response.body.removePrefix("for (;;);")) }.getOrNull()
                ?: throw PlatformParseException("DETAIL_EMPTY",
                    "Instagram 接口返回了网页而非媒体数据，请确认网络和登录状态后重试", response.statusCode)
            val media = InstagramMediaNormalizer.findMedia(root, source)
            if (media != null) return finish(media, "graphql", response.statusCode)
            attempts += ParserAttempt("graphql", false, response.statusCode, "DETAIL_EMPTY")
            InstagramMediaNormalizer.gatingError(root, source)?.let { throw it }
            throw PlatformParseException("LOGIN_REQUIRED",
                "Instagram 未返回目标作品媒体，请在首页登录并确认该账号能查看此作品后重试")
        } catch (error: PlatformParseException) {
            parseFailure(platform, error.code, error.message.orEmpty()).copy(
                parserAttempts = attempts + ParserAttempt("request_failed", false, error.statusCode, error.code))
        } catch (error: IOException) {
            parseFailure(platform, "NETWORK", "Instagram 网络请求失败：${error.javaClass.simpleName}")
                .copy(parserAttempts = attempts)
        } catch (error: Exception) {
            parseFailure(platform, "PARSE_FAILED", "Instagram 数据解析失败：${error.javaClass.simpleName}")
                .copy(parserAttempts = attempts)
        }
    }

    private fun headers(referer: String) = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Accept" to "*/*", "Referer" to referer, "X-IG-App-ID" to "936619743392459",
        "X-ASBD-ID" to "359341", "X-IG-WWW-Claim" to "0", "Origin" to "https://www.instagram.com",
    )

    private fun checkStatus(response: ParserHttpResponse) {
        when (response.statusCode) {
            401 -> throw PlatformParseException("LOGIN_REQUIRED", "Instagram 要求登录后访问", 401)
            403, 429 -> throw PlatformParseException("AUTH_OR_RISK", "Instagram 暂时限制了访问，请稍后重试", response.statusCode)
        }
        if (response.statusCode !in 200..299 && response.statusCode != 404) {
            throw PlatformParseException("HTTP_ERROR", "Instagram 请求失败（HTTP ${response.statusCode}）", response.statusCode)
        }
    }
}
