package com.local.douyindownloader

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

internal data class ResolvedXPost(
    val id: String,
    val canonicalUrl: String,
)

internal object XSourceResolver {
    private val STATUS_PATH = Regex("/(?:[^/]+/)?status(?:es)?/(\\d+)(?:/|$)")

    fun resolve(url: String): ResolvedXPost {
        if (SourcePlatform.fromUrl(url) != SourcePlatform.X) {
            throw PlatformParseException("UNSUPPORTED_URL", "没有找到 X 帖子链接")
        }
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw PlatformParseException("UNSUPPORTED_URL", "X 链接格式无效")
        val id = STATUS_PATH.find(uri.path.orEmpty())?.groupValues?.get(1).orEmpty()
        if (id.isBlank()) {
            throw PlatformParseException("UNSUPPORTED_URL", "当前只支持 X 单条帖子链接")
        }
        return ResolvedXPost(id, "https://x.com/i/status/$id")
    }
}

internal object XMediaNormalizer {
    fun normalize(payload: JSONObject, source: ResolvedXPost): ParseResult {
        unavailableReason(payload)?.let { reason ->
            throw PlatformParseException("CONTENT_UNAVAILABLE", reason)
        }
        val post = unwrapRepost(payload)
        val postId = post.optString("id_str").ifBlank {
            post.optString("rest_id").ifBlank { source.id }
        }
        val user = post.optJSONObject("user") ?: JSONObject()
        val screenName = user.optString("screen_name")
        val media = post.optJSONArray("mediaDetails")
            ?: post.optJSONObject("extended_entities")?.optJSONArray("media")
            ?: JSONArray()
        val attachments = buildList {
            repeat(media.length()) { index ->
                media.optJSONObject(index)?.let { item ->
                    normalizeAttachment(item, index)?.let(::add)
                }
            }
        }
        if (attachments.isEmpty()) {
            throw PlatformParseException(
                "MEDIA_EMPTY",
                "该帖子没有可下载的 X 原生图片或视频",
            )
        }
        val videos = attachments.filter { it.kind != MediaAttachmentKind.IMAGE }
        if (videos.any { it.variants.isEmpty() }) {
            throw PlatformParseException("HLS_ONLY", "该帖子的视频仅提供 HLS 流，当前版本暂不支持")
        }
        val images = attachments.filter { it.kind == MediaAttachmentKind.IMAGE }
        val canonical = if (screenName.isNotBlank()) {
            "https://x.com/$screenName/status/$postId"
        } else {
            "https://x.com/i/status/$postId"
        }
        val avatar = user.optString("profile_image_url_https")
            .replace("_normal.", ".")
        val description = post.optString("full_text").ifBlank { post.optString("text") }.trim()
        val cover = attachments.firstOrNull()?.let { attachment ->
            attachment.coverUrl.ifBlank { attachment.imageCandidates.firstOrNull().orEmpty() }
        }.orEmpty()
        return ParseResult(
            ok = true,
            platform = SourcePlatform.X,
            contentId = postId,
            canonicalUrl = canonical,
            referer = canonical,
            kind = if (videos.isNotEmpty()) MediaKind.VIDEO else MediaKind.IMAGE,
            author = user.optString("name"),
            authorAccountId = screenName,
            authorStableId = user.optString("id_str").ifBlank { user.optString("rest_id") },
            authorProfileUrl = screenName.takeIf(String::isNotBlank)?.let { "https://x.com/$it" }.orEmpty(),
            authorAvatarUrl = avatar,
            description = description,
            coverUrl = cover,
            variants = videos.firstOrNull()?.variants.orEmpty(),
            imageUrls = images.mapNotNull { it.imageCandidates.firstOrNull() },
            imageCandidates = images.map(MediaAttachment::imageCandidates),
            attachments = attachments,
            responseShape = JSONObject().apply {
                put("source", "x")
                put("attachments", attachments.size)
                put("images", images.size)
                put("videos", videos.count { it.kind == MediaAttachmentKind.VIDEO })
                put("gifs", videos.count { it.kind == MediaAttachmentKind.GIF })
            }.toString(),
        )
    }

    fun fromGraphQl(root: JSONObject, source: ResolvedXPost): JSONObject {
        val result = root.optJSONObject("data")
            ?.optJSONObject("tweetResult")
            ?.optJSONObject("result")
            ?: throw graphQlError(root)
        return graphQlPost(result, source.id)
    }

    private fun graphQlPost(rawResult: JSONObject, expectedId: String): JSONObject {
        if (rawResult.optString("__typename") == "TweetUnavailable") {
            when (rawResult.optString("reason")) {
                "Protected" -> throw PlatformParseException(
                    "LOGIN_REQUIRED",
                    "该 X 帖子属于受保护账号，需要有查看权限的账号登录",
                )
                "NsfwLoggedOut", "NsfwViewerHasNoStatedAge" -> throw PlatformParseException(
                    "LOGIN_REQUIRED",
                    "该 X 帖子需要登录并满足账号年龄设置",
                )
            }
        }
        unavailableReason(rawResult)?.let { reason ->
            throw PlatformParseException("CONTENT_UNAVAILABLE", reason)
        }
        val result = if (rawResult.optString("__typename") == "TweetWithVisibilityResults") {
            rawResult.optJSONObject("tweet") ?: rawResult
        } else {
            rawResult
        }
        val legacy = result.optJSONObject("legacy")
            ?: throw PlatformParseException("RESPONSE_CHANGED", "X 返回的数据结构已变化")
        val repostResult = legacy.optJSONObject("retweeted_status_result")
            ?.optJSONObject("result")
        if (repostResult != null) return graphQlPost(repostResult, expectedId)
        val normalized = JSONObject(legacy.toString())
        normalized.put("rest_id", result.optString("rest_id").ifBlank { expectedId })
        val userResult = result.optJSONObject("core")
            ?.optJSONObject("user_results")
            ?.optJSONObject("result")
        val user = userResult?.optJSONObject("legacy")?.let { JSONObject(it.toString()) }
            ?: JSONObject()
        if (user.optString("id_str").isBlank()) {
            user.put("id_str", userResult?.optString("rest_id").orEmpty())
        }
        normalized.put("user", user)
        return normalized
    }

    private fun normalizeAttachment(item: JSONObject, index: Int): MediaAttachment? {
        val type = item.optString("type")
        val mediaId = item.optString("id_str").ifBlank { "media_${index + 1}" }
        val cover = originalImageUrl(item.optString("media_url_https"))
        return when (type) {
            "photo" -> MediaAttachment(
                id = mediaId,
                index = index,
                kind = MediaAttachmentKind.IMAGE,
                coverUrl = cover,
                imageCandidates = listOfNotNull(cover.takeIf(String::isNotBlank)),
            )
            "video", "animated_gif" -> {
                val allVariants = item.optJSONObject("video_info")?.optJSONArray("variants") ?: JSONArray()
                var hlsFound = false
                val variants = buildList {
                    repeat(allVariants.length()) { variantIndex ->
                        val variant = allVariants.optJSONObject(variantIndex) ?: return@repeat
                        val url = variant.optString("url")
                        if (url.contains(".m3u8", ignoreCase = true)) {
                            hlsFound = true
                            return@repeat
                        }
                        if (url.isBlank() || !variant.optString("content_type").contains("video/mp4")) {
                            return@repeat
                        }
                        val dimensions = VIDEO_DIMENSIONS.find(url)
                        add(
                            MediaVariant(
                                width = dimensions?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                                height = dimensions?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                                bitrate = variant.optInt("bitrate"),
                                fps = 0,
                                codec = "H.264",
                                size = 0L,
                                sizeSource = "unknown",
                                urls = listOf(url),
                            ),
                        )
                    }
                }.distinctBy { it.urls.firstOrNull() }.sortedWith(
                    compareByDescending<MediaVariant> { it.width * it.height }
                        .thenByDescending(MediaVariant::bitrate),
                )
                MediaAttachment(
                    id = mediaId,
                    index = index,
                    kind = if (type == "animated_gif") MediaAttachmentKind.GIF else MediaAttachmentKind.VIDEO,
                    coverUrl = cover,
                    variants = variants,
                ).takeIf { variants.isNotEmpty() || hlsFound }
            }
            else -> null
        }
    }

    private fun unwrapRepost(payload: JSONObject): JSONObject =
        payload.optJSONObject("retweeted_status") ?: payload

    private fun unavailableReason(payload: JSONObject): String? {
        val type = payload.optString("__typename")
        return when {
            type == "TweetTombstone" || payload.has("tombstone") -> "该 X 帖子不可用、已删除或当前账号无权查看"
            type == "TweetUnavailable" -> when (payload.optString("reason")) {
                "Protected" -> "该 X 帖子属于受保护账号，需要有权限的账号登录"
                "NsfwLoggedOut", "NsfwViewerHasNoStatedAge" -> "该 X 帖子需要登录并满足年龄设置"
                else -> "该 X 帖子当前不可用"
            }
            else -> null
        }
    }

    private fun graphQlError(root: JSONObject): PlatformParseException {
        val errors = root.optJSONArray("errors") ?: JSONArray()
        val message = buildList {
            repeat(errors.length()) { index ->
                errors.optJSONObject(index)?.optString("message")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }.distinct().joinToString("；")
        return when {
            message.contains("authorized", ignoreCase = true) -> PlatformParseException(
                "LOGIN_REQUIRED",
                "该 X 帖子需要登录或当前账号没有查看权限",
            )
            else -> PlatformParseException(
                "RESPONSE_CHANGED",
                message.ifBlank { "X 返回的数据结构已变化" },
            )
        }
    }

    private fun originalImageUrl(url: String): String {
        if (url.isBlank()) return ""
        val withoutName = url
            .replace(Regex("([?&])name=[^&]*&?"), "$1")
            .trimEnd('?', '&')
        return withoutName + if ('?' in withoutName) "&name=orig" else "?name=orig"
    }

    private val VIDEO_DIMENSIONS = Regex("/(\\d+)x(\\d+)/")
}

@Singleton
internal class XPlatformParser @Inject constructor(
    private val httpClient: ParserHttpClient,
) : PlatformParser {
    override val platform = SourcePlatform.X

    override fun parse(
        shareText: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): ParseResult {
        val attempts = mutableListOf<ParserAttempt>()
        return try {
            val sourceUrl = extractSupportedSource(shareText)
                ?.takeIf { it.platform == platform }
                ?.url
                ?: throw PlatformParseException("UNSUPPORTED_URL", "没有找到 X 帖子链接")
            val source = XSourceResolver.resolve(sourceUrl)
            val syndication = runCatching { fetchSyndication(source) }
            val publicPayload = syndication.getOrNull() ?: JSONObject()
            val publicNormalization = runCatching {
                XMediaNormalizer.normalize(publicPayload, source)
            }
            val publicResult = publicNormalization.getOrNull()
            val conclusivePublicError = publicNormalization.exceptionOrNull()
                ?.let { it as? PlatformParseException }
                ?.takeIf { it.code in setOf("MEDIA_EMPTY", "HLS_ONLY") }
            if (conclusivePublicError != null) {
                attempts += ParserAttempt(
                    "syndication",
                    true,
                    statusCode = 200,
                    errorCode = conclusivePublicError.code,
                )
                return parseFailure(
                    platform,
                    conclusivePublicError.code,
                    conclusivePublicError.message.orEmpty(),
                ).copy(parserAttempts = attempts)
            }
            if (publicResult != null) {
                attempts += ParserAttempt("syndication", true, 200)
                hydrateSizes(publicResult).copy(parserAttempts = attempts)
            } else {
                attempts += ParserAttempt(
                    "syndication",
                    false,
                    statusCode = 0,
                    errorCode = when {
                        syndication.isFailure -> "NETWORK"
                        publicPayload.length() == 0 -> "DETAIL_EMPTY"
                        else -> "CONTENT_UNAVAILABLE"
                    },
                )
                val graphQl = fetchGraphQl(source, cookieHeader)
                val normalized = XMediaNormalizer.normalize(
                    XMediaNormalizer.fromGraphQl(graphQl, source),
                    source,
                )
                attempts += ParserAttempt("graphql", true, 200)
                hydrateSizes(normalized).copy(parserAttempts = attempts)
            }
        } catch (error: PlatformParseException) {
            val needsSessionConfirmation = error.code == "CONTENT_UNAVAILABLE" &&
                detectPlatformCredential(SourcePlatform.X, cookieHeader) != PlatformCredentialState.DETECTED
            val finalCode = if (needsSessionConfirmation) "LOGIN_REQUIRED" else error.code
            val finalMessage = if (needsSessionConfirmation) {
                "公开访问无法读取该 X 帖子，请登录后重试以确认内容是否可用"
            } else {
                error.message.orEmpty()
            }
            parseFailure(platform, finalCode, finalMessage).copy(
                parserAttempts = attempts + ParserAttempt(
                    strategy = "request_failed",
                    selected = false,
                    statusCode = error.statusCode,
                    errorCode = finalCode,
                ),
            )
        } catch (error: IOException) {
            parseFailure(platform, "NETWORK", "X 网络请求失败：${error.javaClass.simpleName}")
                .copy(parserAttempts = attempts)
        } catch (error: Exception) {
            parseFailure(platform, "PARSE_FAILED", error.message ?: error.javaClass.simpleName)
                .copy(parserAttempts = attempts)
        }
    }

    private fun fetchSyndication(source: ResolvedXPost): JSONObject {
        val response = httpClient.get(
            "https://cdn.syndication.twimg.com/tweet-result?id=${source.id}&token=0&lang=zh-cn",
            headers = mapOf(
                "User-Agent" to "Googlebot",
                "Accept" to "application/json, text/plain, */*",
            ),
            timeoutSeconds = 20,
        )
        if (response.statusCode !in 200..299) {
            return JSONObject()
        }
        return runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
    }

    private fun fetchGraphQl(source: ResolvedXPost, cookieHeader: String): JSONObject {
        val hasLoggedInSession = cookieValue(cookieHeader, "auth_token") != null &&
            cookieValue(cookieHeader, "ct0") != null
        val effectiveCookieHeader = cookieHeader.takeIf { hasLoggedInSession }.orEmpty()
        val headers = linkedMapOf(
            "Authorization" to "Bearer $WEB_BEARER_TOKEN",
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "zh-cn",
        )
        if (!hasLoggedInSession) {
            val guestResponse = httpClient.post(
                "https://api.x.com/1.1/guest/activate.json",
                headers = headers,
                timeoutSeconds = 20,
            )
            if (guestResponse.statusCode !in 200..299) {
                throw statusError(guestResponse.statusCode)
            }
            val guestToken = runCatching { JSONObject(guestResponse.body).optString("guest_token") }
                .getOrDefault("")
            if (guestToken.isBlank()) {
                throw PlatformParseException("LOGIN_REQUIRED", "X 匿名访问受限，请登录后重试")
            }
            headers["x-guest-token"] = guestToken
        } else {
            cookieValue(cookieHeader, "ct0")?.let { headers["x-csrf-token"] = it }
            headers["x-twitter-auth-type"] = "OAuth2Session"
        }
        val query = buildGraphQlQuery(source.id)
        val response = httpClient.get(
            "https://x.com/i/api/graphql/$GRAPHQL_ENDPOINT/TweetResultByRestId?$query",
            headers = headers,
            cookieHeader = effectiveCookieHeader,
            timeoutSeconds = 25,
        )
        if (response.statusCode !in 200..299) throw statusError(response.statusCode)
        return runCatching { JSONObject(response.body) }.getOrElse {
            throw PlatformParseException("RESPONSE_CHANGED", "X 返回了无法识别的数据")
        }
    }

    private fun hydrateSizes(result: ParseResult): ParseResult {
        val hydrated = result.attachments.map { attachment ->
            if (attachment.kind == MediaAttachmentKind.IMAGE) return@map attachment
            attachment.copy(
                variants = MediaSizeHydrator.hydrate(attachment.variants) { urls ->
                    urls.take(2).firstNotNullOfOrNull { url ->
                        runCatching {
                            httpClient.probeContentLength(
                                url,
                                headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to result.referer,
                                ),
                            )
                        }.getOrDefault(0L).takeIf { it > 0L }
                    } ?: 0L
                },
            )
        }
        val firstVideo = hydrated.firstOrNull { it.kind != MediaAttachmentKind.IMAGE }
        return result.copy(
            attachments = hydrated,
            variants = firstVideo?.variants.orEmpty(),
        )
    }

    private fun buildGraphQlQuery(id: String): String {
        val variables = JSONObject().apply {
            put("tweetId", id)
            put("withCommunity", false)
            put("includePromotedContent", false)
            put("withVoice", false)
        }
        val features = JSONObject().apply {
            put("creator_subscriptions_tweet_preview_api_enabled", true)
            put("tweetypie_unmention_optimization_enabled", true)
            put("responsive_web_edit_tweet_api_enabled", true)
            put("graphql_is_translatable_rweb_tweet_is_translatable_enabled", true)
            put("view_counts_everywhere_api_enabled", true)
            put("longform_notetweets_consumption_enabled", true)
            put("responsive_web_twitter_article_tweet_consumption_enabled", false)
            put("tweet_awards_web_tipping_enabled", false)
            put("freedom_of_speech_not_reach_fetch_enabled", true)
            put("standardized_nudges_misinfo", true)
            put("tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled", true)
            put("longform_notetweets_rich_text_read_enabled", true)
            put("longform_notetweets_inline_media_enabled", true)
            put("responsive_web_graphql_exclude_directive_enabled", true)
            put("verified_phone_label_enabled", false)
            put("responsive_web_media_download_video_enabled", false)
            put("responsive_web_graphql_skip_user_profile_image_extensions_enabled", false)
            put("responsive_web_graphql_timeline_navigation_enabled", true)
            put("responsive_web_enhance_cards_enabled", false)
        }
        val toggles = JSONObject().put("withArticleRichContentState", false)
        return listOf(
            "variables" to variables.toString(),
            "features" to features.toString(),
            "fieldToggles" to toggles.toString(),
        ).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    }

    private fun statusError(status: Int): PlatformParseException = when (status) {
        401, 403 -> PlatformParseException("LOGIN_REQUIRED", "X 匿名访问受限，请登录后重试", status)
        404, 410 -> PlatformParseException("CONTENT_UNAVAILABLE", "该 X 帖子不存在或已删除", status)
        429 -> PlatformParseException("RATE_LIMITED", "X 请求过于频繁，请稍后再试", status)
        else -> PlatformParseException("HTTP_ERROR", "X 请求失败（HTTP $status）", status)
    }

    private fun cookieValue(cookieHeader: String, name: String): String? = cookieHeader
        .split(';')
        .map(String::trim)
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        private const val GRAPHQL_ENDPOINT = "2ICDjqPd81tulZcYrtpTuQ"
        private const val WEB_BEARER_TOKEN =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D" +
                "1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
