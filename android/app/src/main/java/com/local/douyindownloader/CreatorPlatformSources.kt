package com.local.douyindownloader

import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

internal class CreatorSourceException(val code: String, message: String) : IOException(message)

internal interface CreatorPlatformSource {
    val platform: SourcePlatform

    fun resolve(
        query: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot? = null,
    ): CreatorProfile

    fun fetchPage(
        profile: CreatorProfile,
        cursor: String,
        pageNumber: Int,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot? = null,
    ): CreatorPage
}

@Singleton
internal class CreatorSourceRouter @Inject constructor(
    douyin: DouyinCreatorSource,
    xiaohongshu: XiaohongshuCreatorSource,
    zhihu: ZhihuCreatorSource,
    x: XCreatorSource,
    instagram: InstagramCreatorSource,
    bilibili: BilibiliCreatorSource,
) {
    private val sources = listOf(douyin, xiaohongshu, zhihu, x, instagram, bilibili)
        .associateBy(CreatorPlatformSource::platform)

    fun resolve(
        platform: SourcePlatform,
        query: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot? = null,
    ): CreatorProfile = sources.getValue(platform).resolve(query, cookieHeader, pageSnapshot)

    fun fetchPage(
        profile: CreatorProfile,
        cursor: String,
        pageNumber: Int,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot? = null,
    ): CreatorPage = sources.getValue(profile.platform)
        .fetchPage(profile, cursor, pageNumber, cookieHeader, pageSnapshot)
}

@Singleton
internal class DouyinCreatorSource @Inject constructor(
    private val http: ParserHttpClient,
) : CreatorPlatformSource {
    override val platform = SourcePlatform.DOUYIN

    override fun resolve(
        query: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): CreatorProfile {
        val direct = douyinCreatorStableId(query)
        val redirected = if (direct.isBlank() && query.startsWith("http", ignoreCase = true)) {
            val response = http.get(query, webHeaders(platform.referer), cookieHeader, 25)
            checkCreatorResponse(response, platform)
            douyinCreatorStableId(response.finalUrl)
        } else ""
        val secUid = direct.ifBlank { redirected }
        if (secUid.isBlank()) {
            throw CreatorSourceException("AUTHOR_QUERY_UNSUPPORTED", "抖音请粘贴作者主页链接")
        }
        return fetchProfile(secUid, cookieHeader)
    }

    private fun fetchProfile(
        secUid: String,
        cookieHeader: String,
        previous: CreatorProfile? = null,
    ): CreatorProfile {
        val root = requestJsonWithTransientRetry(
            url = { signedUrl(PROFILE_ENDPOINT, linkedMapOf("sec_user_id" to secUid)) },
            cookieHeader = cookieHeader,
        )
        val user = findObjects(root).firstOrNull { item ->
            item.firstString("sec_uid", "secUid") == secUid &&
                item.firstString("nickname", "name").isNotBlank()
        } ?: root.firstObject("user", "user_info")
            ?: throw CreatorSourceException("AUTHOR_NOT_FOUND", "抖音没有返回作者资料")
        return profileFromUser(user, secUid, previous)
    }

    override fun fetchPage(
        profile: CreatorProfile,
        cursor: String,
        pageNumber: Int,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): CreatorPage {
        val params = linkedMapOf(
            "sec_user_id" to profile.stableId,
            "max_cursor" to cursor.ifBlank { "0" },
            "count" to "18",
            "locate_query" to "false",
            "show_live_replay_strategy" to "1",
            "need_time_list" to "1",
            "time_list_query" to "0",
            "publish_video_strategy_type" to "2",
        )
        val root = requestJsonWithTransientRetry(
            url = { signedUrl(POST_ENDPOINT, params) },
            cookieHeader = cookieHeader,
        )
        val now = System.currentTimeMillis()
        val details = root.firstArray("aweme_list", "awemeList")?.values().orEmpty()
            .mapNotNull { it as? JSONObject }
        val works = details
            .mapNotNull { detail ->
                val id = detail.firstString("aweme_id", "awemeId")
                if (id.isBlank()) return@mapNotNull null
                val normalized = runCatching {
                    DouyinMediaNormalizer.normalize(detail, id, MediaKind.VIDEO)
                }.getOrNull() ?: return@mapNotNull null
                CreatorWork(
                    key = creatorWorkKey(platform, id),
                    creatorKey = profile.key,
                    platform = platform,
                    contentId = id,
                    canonicalUrl = normalized.canonicalUrl,
                    kind = normalized.kind,
                    title = normalized.description.ifBlank { "抖音作品 $id" },
                    coverUrl = normalized.coverUrl,
                    publishedAt = detail.optLong("create_time").let { if (it > 0L) it * 1_000L else 0L },
                    durationMs = detail.optJSONObject("video")?.optLong("duration") ?: 0L,
                    approximateBytes = normalized.variants.maxOfOrNull(MediaVariant::size) ?: 0L,
                    lastSeenAt = now,
                    pageNumber = pageNumber,
                )
            }
        val listAuthor = details.asSequence()
            .mapNotNull { it.firstObject("author") }
            .firstOrNull { it.firstString("sec_uid", "secUid") == profile.stableId }
            ?: details.firstNotNullOfOrNull { it.firstObject("author") }
        val responseProfile = root.firstObject("user", "user_info")
            ?.let { profileFromUser(it, profile.stableId, profile) }
            ?: listAuthor?.let { profileFromUser(it, profile.stableId, profile) }
            ?: profile
        val refreshedProfile = if (pageNumber == 1) {
            runCatching { fetchProfile(profile.stableId, cookieHeader, responseProfile) }
                .getOrDefault(responseProfile)
        } else {
            responseProfile
        }.copy(refreshedAt = now, refreshError = "")
        return CreatorPage(
            profile = refreshedProfile,
            works = works,
            pageNumber = pageNumber,
            cursor = cursor,
            nextCursor = root.firstValue("max_cursor", "maxCursor")?.toString().orEmpty(),
            hasMore = root.optInt("has_more", root.optInt("hasMore")) == 1 || root.optBoolean("has_more"),
        )
    }

    private fun profileFromUser(
        user: JSONObject,
        secUid: String,
        previous: CreatorProfile? = null,
    ): CreatorProfile {
        val stableId = user.firstString("sec_uid", "secUid").ifBlank { secUid }
        val accountId = user.firstString("unique_id", "uniqueId")
            .ifBlank { user.firstString("short_id", "shortId") }
            .takeUnless { it == "0" }.orEmpty()
        val avatarUrl = DouyinMediaNormalizer.addressUrls(
            user.firstValue("avatar_larger", "avatarLarger", "avatar_medium", "avatarMedium"),
        ).firstOrNull().orEmpty()
        val location = user.firstString("ip_location", "ipLocation", "city")
            .replace(Regex("^IP\\s*属地[：:]?\\s*"), "")
        val metrics = listOfNotNull(
            metric("粉丝", user.firstValue("follower_count", "followerCount")),
            metric("关注", user.firstValue("following_count", "followingCount")),
            metric("获赞", user.firstValue("total_favorited", "totalFavorited")),
            metric("作品", user.firstValue("aweme_count", "awemeCount")),
        )
        val now = System.currentTimeMillis()
        return CreatorProfile(
            key = creatorKey(platform, stableId),
            platform = platform,
            stableId = stableId,
            accountId = accountId.ifBlank { previous?.accountId.orEmpty() },
            profileUrl = "https://www.douyin.com/user/$stableId",
            directoryName = previous?.directoryName.orEmpty(),
            avatarUrl = avatarUrl.ifBlank { previous?.avatarUrl.orEmpty() },
            nickname = user.firstString("nickname", "name").ifBlank { previous?.nickname.orEmpty() },
            bio = user.firstString("signature", "desc").ifBlank { previous?.bio.orEmpty() },
            location = location.ifBlank { previous?.location.orEmpty() },
            metrics = mergeCreatorMetrics(previous?.metrics.orEmpty(), metrics),
            accountStatus = if (user.optBoolean("is_block") || user.optBoolean("is_blocked")) {
                CreatorAccountStatus.RESTRICTED
            } else CreatorAccountStatus.PUBLIC,
            followed = previous?.followed ?: true,
            archived = previous?.archived ?: false,
            addedAt = previous?.addedAt ?: now,
            refreshedAt = now,
        )
    }

    private fun requestJson(url: String, cookieHeader: String): JSONObject {
        val response = http.get(url, webHeaders(platform.referer), cookieHeader, 25)
        checkCreatorResponse(response, platform)
        return runCatching { JSONObject(response.body) }
            .getOrElse { throw CreatorSourceException("DETAIL_EMPTY", "抖音作者接口没有返回有效数据") }
    }

    private fun requestJsonWithTransientRetry(
        url: () -> String,
        cookieHeader: String,
    ): JSONObject {
        return try {
            requestJson(url(), cookieHeader)
        } catch (error: CreatorSourceException) {
            if (error.code != "AUTH_OR_RISK") throw error
            Thread.sleep(650L + RETRY_RANDOM.nextInt(301))
            requestJson(url(), cookieHeader)
        }
    }

    private fun signedUrl(endpoint: String, extras: LinkedHashMap<String, String>): String {
        val params = commonDouyinParams().apply { putAll(extras) }
        val text = params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return "$endpoint?$text&a_bogus=${encode(ABogusSigner().sign(text, USER_AGENT))}"
    }

    companion object {
        private const val USER_AGENT = DouyinPlatformParser.USER_AGENT
        private const val PROFILE_ENDPOINT = "https://www.douyin.com/aweme/v1/web/user/profile/other/"
        private const val POST_ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/post/"
        private val RETRY_RANDOM = SecureRandom()
    }
}

@Singleton
internal class XiaohongshuCreatorSource @Inject constructor(
    private val http: ParserHttpClient,
) : CreatorPlatformSource {
    override val platform = SourcePlatform.XIAOHONGSHU

    override fun resolve(
        query: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): CreatorProfile {
        val account = query.trim()
        val isUrl = query.startsWith("http", ignoreCase = true)
        val directUserId = xiaohongshuCreatorStableId(query)
        val response = if (isUrl) {
            http.get(query.trim(), webHeaders(platform.referer), cookieHeader, 25).also {
                checkCreatorResponse(it, platform)
            }
        } else null
        val targetUserId = directUserId.ifBlank {
            response?.finalUrl?.let(::xiaohongshuCreatorStableId).orEmpty()
        }
        val state = when {
            response != null -> XiaohongshuMediaParser.extractInitialState(response.body)
            pageSnapshot != null -> XiaohongshuMediaParser.parseStatePayload(pageSnapshot.initialData)
            else -> null
        } ?: throw CreatorSourceException(
            if (isUrl) "DETAIL_EMPTY" else "BROWSER_RESOLVE_REQUIRED",
            if (isUrl) "小红书页面没有返回作者状态，请登录后重试" else "需要通过小红书页面精确查找该账号",
        )
        val users = findObjects(state).filter { item ->
            item.firstString("userId", "user_id", "id").isNotBlank() &&
                item.firstString("nickname", "nickName", "name").isNotBlank()
        }
        val user = users.firstOrNull { item ->
            val id = item.firstString("userId", "user_id", "id")
            (targetUserId.isNotBlank() && id == targetUserId) ||
                (!isUrl && item.firstString("redId", "red_id") == account)
        } ?: throw CreatorSourceException(
            "AUTHOR_NOT_FOUND",
            "没有找到完全匹配的小红书作者，请检查小红书号或改用作者主页链接",
        )
        val resolvedProfileUrl = response?.finalUrl
            ?.takeIf { xiaohongshuCreatorStableId(it) == user.firstString("userId", "user_id", "id") }
            .orEmpty()
        return profileFromUser(user, previous = null, resolvedProfileUrl = resolvedProfileUrl)
    }

    override fun fetchPage(
        profile: CreatorProfile,
        cursor: String,
        pageNumber: Int,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): CreatorPage {
        if (cursor.isNotBlank()) {
            throw CreatorSourceException(
                "CURSOR_REQUIRES_BROWSER",
                "小红书后续页面需要平台浏览器签名；请稍后刷新主页继续获取",
            )
        }
        val state = if (pageSnapshot != null) {
            val snapshotUserId = xiaohongshuCreatorStableId(pageSnapshot.finalUrl)
            if (snapshotUserId.isNotBlank() && snapshotUserId != profile.stableId) {
                throw CreatorSourceException("AUTHOR_NOT_FOUND", "小红书页面没有停留在目标作者主页")
            }
            XiaohongshuMediaParser.parseStatePayload(pageSnapshot.initialData)
        } else {
            val response = http.get(profile.profileUrl, webHeaders(platform.referer), cookieHeader, 25)
            checkCreatorResponse(response, platform)
            XiaohongshuMediaParser.extractInitialState(response.body)
        } ?: throw CreatorSourceException("DETAIL_EMPTY", "小红书主页没有返回公开作品状态")
        val objects = findObjects(state)
        val user = objects.firstOrNull {
            it.firstString("userId", "user_id", "id") == profile.stableId &&
                it.firstString("nickname", "nickName", "name").isNotBlank()
        }
        val now = System.currentTimeMillis()
        val works = objects.mapNotNull { container ->
            val note = container.firstObject("noteCard", "note_card") ?: container
            val id = note.firstString("noteId", "note_id")
            if (!XHS_NOTE_ID.matches(id)) return@mapNotNull null
            val title = note.firstString("displayTitle", "display_title", "title", "desc")
            val cover = XiaohongshuMediaParser.previewCandidates(
                note.firstValue("cover", "imageList", "image_list"),
            ).firstOrNull().orEmpty()
            val type = note.firstString("type", "noteType", "note_type").lowercase()
            val xsecToken = note.firstString("xsecToken", "xsec_token")
                .ifBlank { container.firstString("xsecToken", "xsec_token") }
            val xsecSource = note.firstString("xsecSource", "xsec_source")
                .ifBlank { container.firstString("xsecSource", "xsec_source") }
            val explicitUrl = note.firstString("noteUrl", "note_url", "shareUrl", "share_url")
                .ifBlank { container.firstString("noteUrl", "note_url", "shareUrl", "share_url") }
            CreatorWork(
                key = creatorWorkKey(platform, id),
                creatorKey = profile.key,
                platform = platform,
                contentId = id,
                canonicalUrl = buildXiaohongshuWorkUrl(id, xsecToken, xsecSource, explicitUrl),
                kind = if (type == "video") MediaKind.VIDEO else MediaKind.IMAGE,
                title = title.ifBlank { "小红书作品 $id" },
                coverUrl = cover,
                publishedAt = note.firstValue("time", "publishTime", "publish_time").jsonLong(),
                durationMs = note.firstValue("duration", "durationMs").jsonLong(),
                lastSeenAt = now,
                pageNumber = pageNumber,
            )
        }.distinctBy(CreatorWork::key)
        if (works.isEmpty()) {
            throw CreatorSourceException(
                "CREATOR_PAGE_EMPTY",
                "小红书没有返回完整作品列表，已保留上次缓存",
            )
        }
        val pageCursor = findObjects(state).asSequence()
            .map { it.firstString("cursor") }
            .firstOrNull(String::isNotBlank).orEmpty()
        return CreatorPage(
            profile = user?.let { profileFromUser(it, profile) }
                ?: profile.copy(refreshedAt = now, refreshError = ""),
            works = works,
            pageNumber = pageNumber,
            cursor = cursor,
            nextCursor = pageCursor,
            // The public profile HTML is currently the only endpoint that does not require
            // Xiaohongshu's page-generated request signature. Do not expose a next-page
            // action that is guaranteed to fail when only an unsigned cursor is available.
            hasMore = false,
        )
    }

    private fun profileFromUser(
        user: JSONObject,
        previous: CreatorProfile?,
        resolvedProfileUrl: String = "",
    ): CreatorProfile {
        val stableId = user.firstString("userId", "user_id", "id")
            .ifBlank { previous?.stableId.orEmpty() }
        if (stableId.isBlank()) throw CreatorSourceException("AUTHOR_NOT_FOUND", "小红书作者缺少稳定标识")
        val now = System.currentTimeMillis()
        val accountId = user.firstString("redId", "red_id").ifBlank { previous?.accountId.orEmpty() }
        return CreatorProfile(
            key = creatorKey(platform, stableId),
            platform = platform,
            stableId = stableId,
            accountId = accountId,
            profileUrl = resolvedProfileUrl.takeIf(String::isNotBlank)
                ?: previous?.profileUrl?.takeIf(String::isNotBlank)
                ?: "https://www.xiaohongshu.com/user/profile/$stableId",
            directoryName = previous?.directoryName.orEmpty(),
            avatarUrl = XiaohongshuMediaParser.previewCandidates(
                user.firstValue("avatar", "avatarUrl", "avatar_url", "image", "imageb", "images"),
            ).firstOrNull().orEmpty(),
            nickname = user.firstString("nickname", "nickName", "name")
                .ifBlank { previous?.nickname.orEmpty() },
            bio = user.firstString("desc", "description", "bio"),
            location = user.firstString("ipLocation", "ip_location", "location"),
            metrics = listOfNotNull(
                metric("粉丝", user.firstValue("fans", "fansCount", "fans_count", "followerCount")),
                metric("关注", user.firstValue("follows", "followsCount", "followingCount")),
                metric("获赞与收藏", user.firstValue("interaction", "likedAndCollected", "liked_count")),
            ),
            followed = previous?.followed ?: true,
            archived = previous?.archived ?: false,
            addedAt = previous?.addedAt ?: now,
            refreshedAt = now,
        )
    }

    companion object {
        private val XHS_NOTE_ID = Regex("[0-9a-fA-F]{24}")
    }
}

private fun buildXiaohongshuWorkUrl(
    noteId: String,
    xsecToken: String,
    xsecSource: String = "",
    explicitUrl: String = "",
): String {
    explicitUrl.takeIf { candidate ->
        XiaohongshuMediaParser.isShare(candidate) &&
            XiaohongshuMediaParser.noteIdFromUrl(candidate) == noteId &&
            parseUrlQuery(runCatching { URI(candidate).rawQuery }.getOrNull())["xsec_token"].orEmpty().isNotBlank()
    }?.let { return it }

    val base = "https://www.xiaohongshu.com/discovery/item/$noteId"
    if (xsecToken.isBlank()) return base
    val source = xsecSource.ifBlank { "pc_share" }
    return "$base?source=webshare&xhsshare=pc_web" +
        "&xsec_token=${encode(xsecToken)}&xsec_source=${encode(source)}"
}

/**
 * Old creator records used /explore + pc_user even though their token came from a
 * profile-page share context. Upgrade those cached URLs before parsing while leaving
 * already complete discovery links untouched.
 */
internal fun preferredXiaohongshuCreatorWorkUrl(noteId: String, cachedUrl: String): String {
    val uri = runCatching { URI(cachedUrl) }.getOrNull() ?: return cachedUrl
    if (!SourcePlatform.XIAOHONGSHU.matchesHost(uri.host.orEmpty())) return cachedUrl
    val token = parseUrlQuery(uri.rawQuery)["xsec_token"].orEmpty()
    if (token.isBlank()) return cachedUrl
    val query = parseUrlQuery(uri.rawQuery)
    val alreadyPreferred = uri.path.orEmpty().startsWith("/discovery/item/") &&
        query["source"] == "webshare" && query["xhsshare"] == "pc_web" &&
        query["xsec_source"].orEmpty().isNotBlank()
    return if (alreadyPreferred) cachedUrl else buildXiaohongshuWorkUrl(noteId, token)
}

@Singleton
internal class ZhihuCreatorSource @Inject constructor(
    private val http: ParserHttpClient,
) : CreatorPlatformSource {
    override val platform = SourcePlatform.ZHIHU

    override fun resolve(
        query: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): CreatorProfile {
        val token = ZHIHU_PROFILE.find(query)?.groupValues?.get(1)
            ?: throw CreatorSourceException("AUTHOR_QUERY_UNSUPPORTED", "知乎请粘贴作者主页链接，不能使用可能重复的昵称")
        return fetchProfile(token, cookieHeader)
    }

    private fun fetchProfile(
        token: String,
        cookieHeader: String,
        previous: CreatorProfile? = null,
    ): CreatorProfile {
        val response = http.get(
            "https://www.zhihu.com/api/v4/members/${encode(token)}?include=$ZHIHU_PROFILE_FIELDS",
            webHeaders("https://www.zhihu.com/people/$token"),
            cookieHeader,
            25,
        )
        checkCreatorResponse(response, platform)
        return profileFromUser(JSONObject(response.body), token, previous)
    }

    override fun fetchPage(
        profile: CreatorProfile,
        cursor: String,
        pageNumber: Int,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): CreatorPage {
        val offsets = ZhihuCreatorCursor.decode(cursor)
        val now = System.currentTimeMillis()
        val refreshedProfile = if (pageNumber == 1) {
            runCatching { fetchProfile(profile.stableId, cookieHeader, profile) }.getOrDefault(profile)
        } else {
            profile
        }
        val results = ZHIHU_COLLECTIONS.map { collection ->
            fetchCollection(refreshedProfile, collection, offsets.getValue(collection.key), cookieHeader)
        }
        val successful = results.mapNotNull { it.getOrNull() }
        if (successful.isEmpty()) {
            throw results.firstNotNullOfOrNull { it.exceptionOrNull() }
                ?: CreatorSourceException("DETAIL_EMPTY", "知乎没有返回作者作品")
        }
        val candidates = successful.flatMap { collectionPage ->
            collectionPage.items.mapIndexedNotNull { index, target ->
                zhihuWork(refreshedProfile, target, pageNumber, now)?.let { work ->
                    ZhihuWorkCandidate(collectionPage.key, index, work)
                }
            }
        }.distinctBy { it.work.key }
            .sortedByDescending { it.work.publishedAt }
        val selected = candidates.take(ZHIHU_PAGE_SIZE)
        val works = selected.map(ZhihuWorkCandidate::work)
        if (works.isEmpty() && results.any { it.isFailure }) {
            throw CreatorSourceException("AUTH_OR_RISK", "知乎部分作品接口被拒绝，请登录或刷新知乎环境后重试")
        }
        val nextOffsets = offsets.toMutableMap().apply {
            successful.forEach { collectionPage ->
                val selectedIndexes = selected.asSequence()
                    .filter { it.collectionKey == collectionPage.key }
                    .map(ZhihuWorkCandidate::sourceIndex)
                    .toList()
                val recognizedIndexes = candidates.asSequence()
                    .filter { it.collectionKey == collectionPage.key }
                    .map(ZhihuWorkCandidate::sourceIndex)
                    .toSet()
                val consumed = when {
                    selectedIndexes.isNotEmpty() -> selectedIndexes.max() + 1
                    collectionPage.items.indices.none(recognizedIndexes::contains) -> collectionPage.items.size
                    else -> 0
                }
                val next = when {
                    consumed < collectionPage.items.size -> collectionPage.offset + consumed
                    else -> collectionPage.nextOffset
                }
                put(collectionPage.key, next)
            }
        }
        return CreatorPage(
            profile = refreshedProfile.copy(refreshedAt = now, refreshError = ""),
            works = works,
            pageNumber = pageNumber,
            cursor = cursor,
            nextCursor = ZhihuCreatorCursor.encode(nextOffsets),
            hasMore = nextOffsets.values.any { it >= 0 },
        )
    }

    private fun fetchCollection(
        profile: CreatorProfile,
        collection: ZhihuCollection,
        offset: Int,
        cookieHeader: String,
    ): Result<ZhihuCollectionPage> = runCatching {
        if (offset < 0) return@runCatching ZhihuCollectionPage(collection.key, offset, emptyList(), -1)
        val url = buildString {
            append("https://www.zhihu.com/api/v4/members/")
            append(encode(profile.stableId))
            append('/')
            append(collection.path)
            append("?limit=20&offset=")
            append(offset)
            collection.extraQuery.takeIf(String::isNotBlank)?.let { append('&').append(it) }
        }
        val response = http.get(url, webHeaders(profile.profileUrl), cookieHeader, 25)
        checkCreatorResponse(response, platform)
        val root = runCatching { JSONObject(response.body) }
            .getOrElse { throw CreatorSourceException("DETAIL_EMPTY", "知乎${collection.label}接口没有返回有效数据") }
        val items = root.optJSONArray("data")?.values().orEmpty().mapNotNull { it as? JSONObject }
        val paging = root.optJSONObject("paging") ?: JSONObject()
        val nextOffset = if (paging.optBoolean("is_end", true)) {
            -1
        } else {
            OFFSET.find(paging.optString("next"))?.groupValues?.get(1)?.toIntOrNull()
                ?: (offset + items.size)
        }
        ZhihuCollectionPage(collection.key, offset, items, nextOffset)
    }

    private fun profileFromUser(
        user: JSONObject,
        token: String,
        previous: CreatorProfile? = null,
    ): CreatorProfile {
        val stableId = user.firstString("url_token", "urlToken").ifBlank { token }
        val location = user.firstString("ip_info", "ipInfo")
            .replace(Regex("^IP\\s*属地[：:]?\\s*"), "")
            .ifBlank { user.optJSONArray("locations")?.optJSONObject(0)?.firstString("name").orEmpty() }
        val metrics = listOfNotNull(
            metric("粉丝", user.firstValue("follower_count", "followerCount")),
            metric("关注", user.firstValue("following_count", "followingCount")),
            metric("获赞", user.firstValue("voteup_count", "voteupCount")),
            metric("回答", user.firstValue("answer_count", "answerCount")),
            metric("文章", user.firstValue("articles_count", "articlesCount")),
            metric("提问", user.firstValue("question_count", "questionCount")),
            metric("想法", user.firstValue("pins_count", "pinsCount")),
            metric("专栏", user.firstValue("columns_count", "columnsCount")),
        )
        val now = System.currentTimeMillis()
        return CreatorProfile(
            key = creatorKey(platform, stableId),
            platform = platform,
            stableId = stableId,
            accountId = stableId,
            profileUrl = "https://www.zhihu.com/people/$stableId",
            directoryName = previous?.directoryName.orEmpty(),
            avatarUrl = user.firstString("avatar_url", "avatarUrl").ifBlank { previous?.avatarUrl.orEmpty() },
            nickname = user.firstString("name").ifBlank { previous?.nickname.orEmpty() }.ifBlank { stableId },
            bio = user.firstString("headline", "description").ifBlank { previous?.bio.orEmpty() },
            location = location.ifBlank { previous?.location.orEmpty() },
            metrics = metrics.ifEmpty { previous?.metrics.orEmpty() },
            accountStatus = CreatorAccountStatus.PUBLIC,
            followed = previous?.followed ?: true,
            archived = previous?.archived ?: false,
            refreshedAt = now,
            addedAt = previous?.addedAt ?: now,
        )
    }

    private fun zhihuWork(
        profile: CreatorProfile,
        target: JSONObject,
        pageNumber: Int,
        now: Long,
    ): CreatorWork? {
        val type = target.firstString("type").lowercase()
        val id = target.firstValue("id")?.toString().orEmpty()
        if (id.isBlank()) return null
        val (kind, url) = when (type) {
            "answer" -> MediaKind.DOCUMENT to target.optJSONObject("question")?.let { question ->
                val questionId = question.firstValue("id")?.toString().orEmpty()
                "https://www.zhihu.com/question/$questionId/answer/$id"
            }.orEmpty()
            "article" -> MediaKind.DOCUMENT to "https://zhuanlan.zhihu.com/p/$id"
            "zvideo", "video" -> MediaKind.VIDEO to "https://www.zhihu.com/zvideo/$id"
            "pin" -> MediaKind.DOCUMENT to "https://www.zhihu.com/pin/$id"
            else -> return null
        }
        if (url.isBlank()) return null
        val title = target.optJSONObject("question")?.firstString("title")
            .orEmpty().ifBlank { target.firstString("title", "excerpt", "content") }
        return CreatorWork(
            key = creatorWorkKey(platform, id),
            creatorKey = profile.key,
            platform = platform,
            contentId = id,
            canonicalUrl = url,
            kind = kind,
            title = title.take(120).ifBlank { "知乎作品 $id" },
            coverUrl = target.firstString("image_url", "thumbnail", "cover_url"),
            publishedAt = target.firstValue("created_time", "created", "published_time").jsonLong()
                .let { if (it in 1..9_999_999_999L) it * 1_000L else it },
            durationMs = target.firstValue("duration", "duration_ms").jsonLong(),
            lastSeenAt = now,
            pageNumber = pageNumber,
        )
    }

    companion object {
        private val ZHIHU_PROFILE = Regex("(?:https?://)?(?:www\\.)?zhihu\\.com/people/([^/?#]+)")
        private val OFFSET = Regex("[?&]offset=(\\d+)")
        private const val ZHIHU_PAGE_SIZE = 20
        private const val ZHIHU_PROFILE_FIELDS =
            "locations,educations,employments,follower_count,following_count,voteup_count," +
                "answer_count,articles_count,question_count,pins_count,columns_count"
        private val ZHIHU_COLLECTIONS = listOf(
            ZhihuCollection("answers", "answers", "回答", "sort_by=created_time"),
            ZhihuCollection("articles", "articles", "文章", "sort_by=created_time"),
            ZhihuCollection("pins", "pins", "想法", ""),
            ZhihuCollection("zvideos", "zvideos", "视频", "similar_aggregation=true"),
        )
    }
}

private data class ZhihuCollection(
    val key: String,
    val path: String,
    val label: String,
    val extraQuery: String,
)

private data class ZhihuCollectionPage(
    val key: String,
    val offset: Int,
    val items: List<JSONObject>,
    val nextOffset: Int,
)

private data class ZhihuWorkCandidate(
    val collectionKey: String,
    val sourceIndex: Int,
    val work: CreatorWork,
)

private object ZhihuCreatorCursor {
    private val keys = listOf("answers", "articles", "pins", "zvideos")

    fun decode(value: String): Map<String, Int> {
        val legacyOffset = value.toIntOrNull()
        if (legacyOffset != null) return keys.associateWith { legacyOffset }
        val root = runCatching { JSONObject(value) }.getOrNull()
        return keys.associateWith { key -> root?.optInt(key, 0) ?: 0 }
    }

    fun encode(offsets: Map<String, Int>): String = JSONObject().apply {
        keys.forEach { key -> put(key, offsets[key] ?: 0) }
    }.toString()
}

internal fun douyinCreatorStableId(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return ""
    if (!SourcePlatform.DOUYIN.matchesHost(uri.host.orEmpty().lowercase())) return ""
    val pathId = Regex("/(?:share/)?user/([^/?#]+)", RegexOption.IGNORE_CASE)
        .find(uri.rawPath.orEmpty())?.groupValues?.get(1).orEmpty()
    if (pathId.isNotBlank()) return decodeUrlComponent(pathId)
    val query = parseUrlQuery(uri.rawQuery)
    return query["sec_uid"].orEmpty().ifBlank { query["sec_user_id"].orEmpty() }
}

internal fun xiaohongshuCreatorStableId(url: String): String {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return ""
    if (!SourcePlatform.XIAOHONGSHU.matchesHost(uri.host.orEmpty().lowercase())) return ""
    return Regex("/user/profile/([0-9a-zA-Z_-]+)", RegexOption.IGNORE_CASE)
        .find(uri.rawPath.orEmpty())?.groupValues?.get(1).orEmpty()
        .let(::decodeUrlComponent)
}

internal fun xiaohongshuCreatorSearchUrl(accountId: String): String =
    "https://www.xiaohongshu.com/search_result?keyword=${encode(accountId.trim())}" +
        "&source=web_search_result_notes&type=55"

private fun parseUrlQuery(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
    .split('&')
    .mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator < 0) null else decodeUrlComponent(part.substring(0, separator)) to
            decodeUrlComponent(part.substring(separator + 1))
    }
    .toMap()

private fun decodeUrlComponent(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private fun metric(label: String, raw: Any?): CreatorMetric? {
    val value = when (raw) {
        null, JSONObject.NULL -> ""
        is JSONObject -> raw.firstValue("count", "value", "num")?.toString().orEmpty()
        else -> raw.toString()
    }
    return value.takeIf { it.isNotBlank() && it != "0" }?.let { CreatorMetric(label, it) }
}

private fun mergeCreatorMetrics(
    previous: List<CreatorMetric>,
    current: List<CreatorMetric>,
): List<CreatorMetric> {
    if (current.isEmpty()) return previous
    val currentLabels = current.mapTo(HashSet(), CreatorMetric::label)
    return current + previous.filterNot { it.label in currentLabels }
}

private fun webHeaders(referer: String): Map<String, String> = mapOf(
    "User-Agent" to DouyinPlatformParser.USER_AGENT,
    "Referer" to referer,
    "Accept" to "application/json,text/plain,text/html,*/*",
    "Accept-Language" to "zh-CN,zh;q=0.9",
)

private fun checkCreatorResponse(response: ParserHttpResponse, platform: SourcePlatform) {
    when (response.statusCode) {
        in 200..299 -> Unit
        401, 403, 429, 461 -> throw CreatorSourceException("AUTH_OR_RISK", "${platform.displayName}登录状态或风控拒绝了请求")
        404, 410 -> throw CreatorSourceException("AUTHOR_NOT_FOUND", "${platform.displayName}作者不存在或已不可访问")
        else -> throw CreatorSourceException("HTTP_ERROR", "${platform.displayName}作者请求失败（HTTP ${response.statusCode}）")
    }
}

private fun commonDouyinParams(): LinkedHashMap<String, String> = linkedMapOf(
    "device_platform" to "webapp",
    "aid" to "6383",
    "channel" to "channel_pc_web",
    "pc_client_type" to "1",
    "version_code" to "290100",
    "version_name" to "29.1.0",
    "cookie_enabled" to "true",
    "screen_width" to "1920",
    "screen_height" to "1080",
    "browser_language" to "zh-CN",
    "browser_platform" to "Win32",
    "browser_name" to "Edge",
    "browser_version" to "130.0.0.0",
    "browser_online" to "true",
    "engine_name" to "Blink",
    "engine_version" to "130.0.0.0",
    "os_name" to "Windows",
    "os_version" to "10",
    "cpu_core_num" to "12",
    "device_memory" to "8",
    "platform" to "PC",
    "downlink" to "10",
    "effective_type" to "4g",
    "round_trip_time" to "100",
    "msToken" to buildString(184) {
        val random = SecureRandom()
        repeat(184) { append(TOKEN_CHARS[random.nextInt(TOKEN_CHARS.length)]) }
    },
)

private fun findObjects(root: Any?): List<JSONObject> {
    val queue = ArrayDeque<Pair<Any, Int>>()
    if (root is JSONObject || root is JSONArray) queue += root as Any to 0
    val result = ArrayList<JSONObject>()
    var visited = 0
    while (queue.isNotEmpty() && visited < 60_000) {
        val (value, depth) = queue.removeFirst()
        visited += 1
        when (value) {
            is JSONObject -> {
                result += value
                if (depth < 14) value.keysInOrder().forEach { key ->
                    val child = value.opt(key)
                    if (child is JSONObject || child is JSONArray) queue += child as Any to depth + 1
                }
            }
            is JSONArray -> if (depth < 14) value.values().forEach { child ->
                if (child is JSONObject || child is JSONArray) queue += child as Any to depth + 1
            }
        }
    }
    return result
}

private fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
