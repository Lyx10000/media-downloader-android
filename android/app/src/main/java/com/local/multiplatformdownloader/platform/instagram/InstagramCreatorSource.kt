package com.local.multiplatformdownloader.platform.instagram


import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.feature.creator.CreatorAccountStatus
import com.local.multiplatformdownloader.feature.creator.CreatorPage
import com.local.multiplatformdownloader.feature.creator.CreatorPlatformSource
import com.local.multiplatformdownloader.feature.creator.CreatorProfile
import com.local.multiplatformdownloader.feature.creator.CreatorSourceException
import com.local.multiplatformdownloader.feature.creator.CreatorWork
import com.local.multiplatformdownloader.feature.creator.SOCIAL_CREATOR_UA
import com.local.multiplatformdownloader.feature.creator.creatorKey
import com.local.multiplatformdownloader.feature.creator.creatorWorkKey
import com.local.multiplatformdownloader.feature.creator.socialCookie
import com.local.multiplatformdownloader.feature.creator.socialCreatorHandle
import com.local.multiplatformdownloader.feature.creator.socialCreatorJson
import com.local.multiplatformdownloader.feature.creator.socialMetric
import com.local.multiplatformdownloader.feature.creator.socialQuery
import com.local.multiplatformdownloader.core.network.firstString
import com.local.multiplatformdownloader.core.network.firstValue
import com.local.multiplatformdownloader.core.network.values

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
internal class InstagramCreatorSource @Inject constructor(
    private val http: ParserHttpClient,
    private val diagnostics: InstagramCreatorDiagnostics,
) : CreatorPlatformSource {
    override val platform = SourcePlatform.INSTAGRAM

    private data class RecentFeed(val handle: String, val cookie: String, val page: CreatorPage, val at: Long)
    @Volatile private var recentFeed: RecentFeed? = null

    override fun resolve(query: String, cookieHeader: String, pageSnapshot: WebPageSnapshot?): CreatorProfile {
        val handle = socialCreatorHandle(platform, query)
        recentFeed = null
        requireSession(cookieHeader)
        val state = InstagramCreatorCursor()
        val connection = requestFeed(handle, "", state, cookieHeader)
        val profile = InstagramCreatorFeedProfile.fromConnection(connection, handle)
        val page = InstagramCreatorNormalizer.page(connection, profile, state, "", 1)
        recentFeed = RecentFeed(handle, cookieHeader, page, System.nanoTime())
        diagnostics.feedPage(page, reused = false)
        return profile
    }

    override fun fetchPage(profile: CreatorProfile, cursor: String, pageNumber: Int,
        cookieHeader: String, pageSnapshot: WebPageSnapshot?): CreatorPage {
        val state = InstagramCreatorCursor.parse(cursor)
        requireSession(cookieHeader)
        if (pageNumber == 1 && cursor.isBlank()) {
            val cached = recentFeed
            recentFeed = null // Confirming a creator consumes this once; explicit refresh remains a real refresh.
            if (cached != null && cached.handle.equals(profile.accountId, true) && cached.cookie == cookieHeader &&
                cached.page.profile.stableId == profile.stableId && System.nanoTime() - cached.at < 30_000_000_000L) {
                return cached.page.copy(profile = profile).also { diagnostics.feedPage(it, reused = true) }
            }
        }
        val connection = requestFeed(profile.accountId, profile.stableId, state, cookieHeader)
        val owner = InstagramCreatorFeedProfile.findOwner(connection, profile.accountId)
        if (owner != null && owner.firstValue("pk", "id").toString() != profile.stableId) {
            throw CreatorSourceException("AUTHOR_CHANGED", "Instagram 用户名对应的账号已变化，原作者本地内容保留")
        }
        return InstagramCreatorNormalizer.page(connection, profile, state, cursor, pageNumber)
            .also { diagnostics.feedPage(it, reused = false) }
    }

    private fun requireSession(cookie: String) {
        if (socialCookie(cookie, "sessionid").isBlank()) throw CreatorSourceException("LOGIN_REQUIRED",
            "Instagram 作者列表需要先在首页登录；登录后自动获取作品，无需手动浏览")
    }

    private fun requestFeed(handle: String, stableId: String, state: InstagramCreatorCursor, cookie: String): JSONObject {
        val reels = state.phase == "reels"
        val variables = JSONObject().put("first", 12)
            .put("__relay_internal__pv__PolarisFeedShareMenurelayprovider", false)
        if (state.after.isNotBlank()) variables.put("after", state.after)
        val connection: String
        val document: String
        if (reels) {
            document = "7845543455542541"
            connection = "xdt_api__v1__clips__user__connection_v2"
            variables.put("data", JSONObject().put("page_size", 12).put("include_feed_video", true)
                .put("target_user_id", stableId))
        } else {
            document = "7898261790222653"
            connection = "xdt_api__v1__feed__user_timeline_graphql_connection"
            variables.put("username", handle)
                .put("data", JSONObject().put("count", 12).put("include_relationship_info", true)
                    .put("latest_besties_reel_media", true).put("latest_reel_media", true))
        }
        val body = socialQuery(mapOf("doc_id" to document, "variables" to variables.toString(), "server_timestamps" to "true"))
        val requestHeaders = headers(cookie) + mapOf("Content-Type" to "application/x-www-form-urlencoded",
            "Referer" to "https://www.instagram.com/$handle/")
        val response = diagnostics.request(if (reels) "reels" else "posts", cookie, requestHeaders, state.after.isNotBlank()) {
            http.post("https://www.instagram.com/graphql/query/", requestHeaders,
                cookie, body.toByteArray(Charsets.UTF_8))
        }
        val data = socialCreatorJson(response, platform).optJSONObject("data")
        return data?.optJSONObject(connection) ?: throw CreatorSourceException("RESPONSE_CHANGED",
            "Instagram 自动接口未返回${if (reels) "Reels" else "帖子"}列表，已缓存内容保留；不会自动循环请求")
    }

    private fun headers(cookie: String) = buildMap {
        put("User-Agent", SOCIAL_CREATOR_UA)
        put("Accept", "application/json")
        put("Referer", "https://www.instagram.com/")
        put("X-IG-App-ID", "936619743392459")
        put("X-Requested-With", "XMLHttpRequest")
        socialCookie(cookie, "csrftoken").takeIf { it.isNotBlank() }?.let { put("X-CSRFToken", it) }
    }
}

/** Only the initial profile transport may request a browser fallback; never retry here. */
internal fun requestInstagramProfilePage(request: () -> ParserHttpResponse): ParserHttpResponse = try {
    request()
} catch (error: IOException) {
    // Preserve interruption and domain errors. The caller also propagates coroutine cancellation.
    if (error is CreatorSourceException || Thread.currentThread().isInterrupted ||
        (error is InterruptedIOException && error !is SocketTimeoutException)) throw error
    throw CreatorSourceException("WEB_PROFILE_REQUIRED", "Instagram 作者网页连接失败，将尝试一次浏览器加载").apply {
        initCause(error)
    }
}

/** Keep the endpoint phase with its opaque cursor; never reuse a posts cursor for Reels. */
internal data class InstagramCreatorCursor(val phase: String = "posts", val after: String = "") {
    fun encode(): String = JSONObject().put("phase", phase).put("after", after).toString()
    companion object {
        fun parse(value: String): InstagramCreatorCursor {
            if (value.isBlank()) return InstagramCreatorCursor()
            val root = runCatching { JSONObject(value) }.getOrNull()
            if (root == null || root.optString("phase") !in setOf("posts", "reels")) {
                throw CreatorSourceException("INVALID_CURSOR", "Instagram 分页缓存无效，请刷新作者首页")
            }
            return InstagramCreatorCursor(root.optString("phase"), root.optString("after"))
        }
    }
}

internal object InstagramCreatorNormalizer {
    fun profile(user: JSONObject, requestedHandle: String): CreatorProfile {
        val id = user.firstValue("pk", "id")?.toString().orEmpty()
        val handle = user.optString("username")
        if (id.isBlank() || !handle.equals(requestedHandle, true)) throw CreatorSourceException(
            "AUTHOR_NOT_FOUND", "Instagram 没有返回匹配的作者资料")
        return CreatorProfile(key = creatorKey(SourcePlatform.INSTAGRAM, id), platform = SourcePlatform.INSTAGRAM,
            stableId = id, accountId = handle, profileUrl = "https://www.instagram.com/$handle/",
            nickname = user.optString("full_name").ifBlank { handle }, bio = user.optString("biography"),
            avatarUrl = user.optString("profile_pic_url_hd").ifBlank { user.optString("profile_pic_url") },
            metrics = listOfNotNull(socialMetric("粉丝", user.optJSONObject("edge_followed_by")?.opt("count") ?: user.opt("follower_count")),
                socialMetric("关注", user.optJSONObject("edge_follow")?.opt("count") ?: user.opt("following_count")),
                socialMetric("帖子", user.optJSONObject("edge_owner_to_timeline_media")?.opt("count") ?: user.opt("media_count"))),
            accountStatus = if (user.optBoolean("is_private")) CreatorAccountStatus.RESTRICTED else CreatorAccountStatus.PUBLIC,
            refreshedAt = System.currentTimeMillis())
    }

    fun page(connection: JSONObject, profile: CreatorProfile, state: InstagramCreatorCursor,
        cursor: String, pageNumber: Int): CreatorPage {
        val edges = connection.optJSONArray("edges")
            ?: throw CreatorSourceException("RESPONSE_CHANGED", "Instagram 列表缺少作品数据，已缓存内容保留")
        val pageInfo = connection.optJSONObject("page_info")
            ?: throw CreatorSourceException("RESPONSE_CHANGED", "Instagram 列表缺少分页状态，不能判断作品是否已经取完")
        val works = edges.values().filterIsInstance<JSONObject>().mapNotNull { edge ->
            val node = edge.optJSONObject("node") ?: return@mapNotNull null
            val item = node.optJSONObject("media") ?: node
            val code = item.firstString("shortcode", "code")
            if (!code.matches(Regex("[A-Za-z0-9_-]{1,28}"))) return@mapNotNull null
            val owner = item.optJSONObject("owner") ?: item.optJSONObject("user")
            val ownerId = owner?.firstValue("pk", "id")?.toString().orEmpty()
            // Some creator-scoped edge nodes omit owner metadata. If supplied it must match.
            if (ownerId.isNotBlank() && ownerId != profile.stableId) return@mapNotNull null
            if (item.optBoolean("is_ad") || item.has("injected")) return@mapNotNull null
            val children = item.optJSONArray("carousel_media")?.values().orEmpty().filterIsInstance<JSONObject>() +
                item.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")?.values().orEmpty()
                    .filterIsInstance<JSONObject>().mapNotNull { it.optJSONObject("node") }
            val video = (listOf(item) + children).any { it.optBoolean("is_video") || it.optInt("media_type") == 2 || it.optString("__typename") == "GraphVideo" }
            val image = item.optString("display_url").ifBlank { item.optString("thumbnail_src") }.ifBlank {
                item.optJSONObject("image_versions2")?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url").orEmpty()
            }.ifBlank { children.firstOrNull()?.optString("display_url").orEmpty() }
            val caption = item.optJSONObject("caption")?.optString("text").orEmpty().ifBlank {
                item.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)
                    ?.optJSONObject("node")?.optString("text").orEmpty()
            }
            CreatorWork(key = creatorWorkKey(SourcePlatform.INSTAGRAM, code), creatorKey = profile.key,
                platform = SourcePlatform.INSTAGRAM, contentId = code, canonicalUrl = "https://www.instagram.com/p/$code/",
                kind = if (video) MediaKind.VIDEO else MediaKind.IMAGE, title = caption.ifBlank { "Instagram 作品 $code" },
                coverUrl = image, publishedAt = item.optLong("taken_at_timestamp", item.optLong("taken_at")) * 1000L,
                durationMs = (item.optDouble("video_duration", 0.0) * 1000).toLong(), pageNumber = pageNumber)
        }.distinctBy(CreatorWork::key)
        val hasNext = pageInfo.optBoolean("has_next_page")
        val after = pageInfo.optString("end_cursor").takeUnless { it == "null" }.orEmpty()
        if (hasNext && (after.isBlank() || after == state.after)) throw CreatorSourceException(
            "RESPONSE_CHANGED", "Instagram 返回无效或重复游标，请稍后刷新")
        val next = when {
            hasNext -> InstagramCreatorCursor(state.phase, after).encode()
            state.phase == "posts" -> InstagramCreatorCursor("reels").encode()
            else -> ""
        }
        return CreatorPage(profile.copy(refreshedAt = System.currentTimeMillis(), refreshError = ""),
            works, pageNumber, cursor, next, next.isNotBlank())
    }
}
