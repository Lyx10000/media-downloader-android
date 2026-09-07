package com.local.multiplatformdownloader.platform.x

import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.network.ParserHttpClient
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
import com.local.multiplatformdownloader.core.network.values

import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
internal class XCreatorSource @Inject constructor(private val http: ParserHttpClient) : CreatorPlatformSource {
    override val platform = SourcePlatform.X

    override fun resolve(query: String, cookieHeader: String, pageSnapshot: WebPageSnapshot?): CreatorProfile {
        val handle = socialCreatorHandle(platform, query)
        val root = request("ck5KkZ8t5cOmoLssopN99Q/UserByScreenName",
            JSONObject().put("screen_name", handle).put("withGrokTranslatedBio", false), cookieHeader)
        val user = root.optJSONObject("data")?.optJSONObject("user")?.optJSONObject("result")
            ?: throw CreatorSourceException("AUTHOR_NOT_FOUND", "X 未返回此作者资料，请检查用户名和访问权限")
        return XCreatorNormalizer.profile(user, handle)
    }

    override fun fetchPage(profile: CreatorProfile, cursor: String, pageNumber: Int,
        cookieHeader: String, pageSnapshot: WebPageSnapshot?): CreatorPage {
        val variables = JSONObject().put("userId", profile.stableId).put("count", 20)
            .put("includePromotedContent", false).put("withClientEventToken", false)
            .put("withBirdwatchNotes", false).put("withVoice", true)
        if (cursor.isNotBlank()) variables.put("cursor", cursor)
        val root = request("jCRhbOzdgOHp6u9H4g2tEg/UserMedia", variables, cookieHeader)
        return XCreatorNormalizer.page(root, profile, cursor, pageNumber)
    }

    private fun request(operation: String, variables: JSONObject, cookie: String): JSONObject {
        val csrf = socialCookie(cookie, "ct0")
        if (socialCookie(cookie, "auth_token").isBlank() || csrf.isBlank()) {
            throw CreatorSourceException("LOGIN_REQUIRED", "X 作者作品列表需要登录，请先在首页登录 X；单条作品下载不受此要求影响")
        }
        val features = JSONObject().apply {
            listOf("responsive_web_graphql_timeline_navigation_enabled", "responsive_web_graphql_exclude_directive_enabled",
                "rweb_tipjar_consumption_enabled", "responsive_web_profile_redirect_enabled", "creator_subscriptions_tweet_preview_api_enabled",
                "responsive_web_edit_tweet_api_enabled", "graphql_is_translatable_rweb_tweet_is_translatable_enabled",
                "view_counts_everywhere_api_enabled", "longform_notetweets_consumption_enabled", "freedom_of_speech_not_reach_fetch_enabled",
                "standardized_nudges_misinfo", "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled",
                "longform_notetweets_rich_text_read_enabled", "longform_notetweets_inline_media_enabled",
                "subscriptions_verification_info_is_identity_verified_enabled", "subscriptions_verification_info_verified_since_enabled"
            ).forEach { put(it, true) }
            listOf("responsive_web_graphql_skip_user_profile_image_extensions_enabled", "verified_phone_label_enabled",
                "responsive_web_twitter_article_tweet_consumption_enabled", "responsive_web_enhance_cards_enabled",
                "communities_web_enable_tweet_community_results_fetch", "responsive_web_grok_analyze_button_fetch_trends_enabled",
                "responsive_web_grok_analyze_post_followups_enabled", "responsive_web_jetfuel_frame", "premium_content_api_read_enabled",
                "responsive_web_grok_share_attachment_enabled", "responsive_web_grok_image_annotation_enabled",
                "responsive_web_grok_analysis_button_from_backend", "articles_preview_enabled", "responsive_web_media_download_video_enabled",
                "tweet_awards_web_tipping_enabled", "creator_subscriptions_quote_tweet_preview_enabled",
                "responsive_web_twitter_article_notes_tab_enabled").forEach { put(it, false) }
        }
        val query = socialQuery(mapOf("variables" to variables.toString(), "features" to features.toString(),
            "fieldToggles" to JSONObject().put("withArticlePlainText", false).put("withAuxiliaryUserLabels", true).toString()))
        val response = http.get("https://x.com/i/api/graphql/$operation?$query", mapOf(
            "Authorization" to "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA",
            "User-Agent" to SOCIAL_CREATOR_UA, "Accept" to "application/json", "Referer" to "https://x.com/",
            "x-csrf-token" to csrf, "x-twitter-auth-type" to "OAuth2Session", "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "en"), cookie)
        return socialCreatorJson(response, platform)
    }
}

internal object XCreatorNormalizer {
    fun profile(user: JSONObject, requestedHandle: String): CreatorProfile {
        val id = user.optString("rest_id")
        val legacy = user.optJSONObject("legacy") ?: JSONObject()
        val core = user.optJSONObject("core") ?: legacy
        val handle = core.optString("screen_name").ifBlank { legacy.optString("screen_name") }
        if (id.isBlank() || !handle.equals(requestedHandle, true)) {
            throw CreatorSourceException("AUTHOR_NOT_FOUND", "X 没有返回匹配的作者，账号可能受限或不可访问")
        }
        return CreatorProfile(key = creatorKey(SourcePlatform.X, id), platform = SourcePlatform.X,
            stableId = id, accountId = handle, profileUrl = "https://x.com/$handle",
            nickname = core.optString("name").ifBlank { legacy.optString("name").ifBlank { handle } },
            avatarUrl = user.optJSONObject("avatar")?.optString("image_url").orEmpty()
                .ifBlank { legacy.optString("profile_image_url_https") }.replace("_normal.", "_400x400."),
            bio = user.optJSONObject("profile_bio")?.optString("description").orEmpty().ifBlank { legacy.optString("description") },
            location = user.optJSONObject("location")?.optString("location").orEmpty().ifBlank { legacy.optString("location") },
            metrics = listOfNotNull(socialMetric("粉丝", legacy.opt("followers_count")),
                socialMetric("关注", legacy.opt("friends_count")), socialMetric("媒体", legacy.opt("media_count"))),
            accountStatus = if (legacy.optBoolean("protected") || user.optJSONObject("privacy")?.optBoolean("protected") == true)
                CreatorAccountStatus.RESTRICTED else CreatorAccountStatus.PUBLIC,
            refreshedAt = System.currentTimeMillis())
    }

    fun page(root: JSONObject, profile: CreatorProfile, cursor: String, pageNumber: Int): CreatorPage {
        val user = root.optJSONObject("data")?.optJSONObject("user")?.optJSONObject("result")
            ?: throw CreatorSourceException("LOGIN_REQUIRED", "X 未返回作者作品，请确认登录和访问权限")
        val timeline = user.optJSONObject("timeline_v2")?.optJSONObject("timeline")
            ?: user.optJSONObject("timeline")?.let { it.optJSONObject("timeline") ?: it }
        val instructions = timeline?.optJSONArray("instructions")
            ?: throw CreatorSourceException("RESPONSE_CHANGED", "X 媒体列表结构未识别，已缓存作品保留")
        val works = linkedMapOf<String, CreatorWork>()
        var next = ""
        fun item(value: JSONObject?) {
            if (value == null || value.has("promotedMetadata")) return
            val raw = value.optJSONObject("tweet_results")?.optJSONObject("result") ?: return
            val tweet = raw.optJSONObject("tweet") ?: raw
            val legacy = tweet.optJSONObject("legacy") ?: return
            if (legacy.has("retweeted_status_result") || legacy.has("retweeted_status_id_str")) return
            val authorId = legacy.optString("user_id_str").ifBlank {
                tweet.optJSONObject("core")?.optJSONObject("user_results")?.optJSONObject("result")?.optString("rest_id").orEmpty()
            }
            if (authorId != profile.stableId) return
            val id = tweet.optString("rest_id").ifBlank { legacy.optString("id_str") }
            if (!id.matches(Regex("\\d+"))) return
            val media = legacy.optJSONObject("extended_entities")?.optJSONArray("media")?.values().orEmpty()
                .mapNotNull { it as? JSONObject }.filter { it.optString("type") in setOf("photo", "video", "animated_gif") }
            if (media.isEmpty()) return
            val video = media.any { it.optString("type") != "photo" }
            works[id] = CreatorWork(key = creatorWorkKey(SourcePlatform.X, id), creatorKey = profile.key,
                platform = SourcePlatform.X, contentId = id, canonicalUrl = "https://x.com/${profile.accountId}/status/$id",
                kind = if (video) MediaKind.VIDEO else MediaKind.IMAGE,
                title = legacy.optString("full_text").ifBlank { "X 作品 $id" },
                coverUrl = media.first().optString("media_url_https"),
                durationMs = media.maxOf { it.optJSONObject("video_info")?.optLong("duration_millis") ?: 0L },
                publishedAt = runCatching { SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)
                    .parse(legacy.optString("created_at"))?.time ?: 0L }.getOrDefault(0L), pageNumber = pageNumber)
        }
        fun entry(entry: JSONObject) {
            val content = entry.optJSONObject("content") ?: return
            if (content.optString("cursorType").equals("Bottom", true)) next = content.optString("value")
            item(content.optJSONObject("itemContent"))
            content.optJSONArray("items")?.values().orEmpty().forEach { value ->
                val moduleItem = (value as? JSONObject)?.optJSONObject("item")
                item(moduleItem?.optJSONObject("itemContent"))
            }
        }
        instructions.values().filterIsInstance<JSONObject>().forEach { instruction ->
            instruction.optJSONArray("entries")?.values().orEmpty().filterIsInstance<JSONObject>().forEach(::entry)
            instruction.optJSONObject("entry")?.let(::entry)
            instruction.optJSONArray("moduleItems")?.values().orEmpty().filterIsInstance<JSONObject>().forEach {
                item(it.optJSONObject("item")?.optJSONObject("itemContent"))
            }
        }
        if (next.isNotBlank() && next == cursor) throw CreatorSourceException("RESPONSE_CHANGED", "X 返回重复游标，请稍后刷新，已缓存作品保留")
        return CreatorPage(profile.copy(refreshedAt = System.currentTimeMillis(), refreshError = ""),
            works.values.toList(), pageNumber, cursor, next, next.isNotBlank())
    }
}
