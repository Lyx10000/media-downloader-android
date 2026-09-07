package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.feature.creator.CreatorAccountStatus
import com.local.multiplatformdownloader.feature.creator.CreatorMetric
import com.local.multiplatformdownloader.feature.creator.CreatorProfile
import com.local.multiplatformdownloader.feature.creator.CreatorSourceException
import com.local.multiplatformdownloader.feature.creator.CreatorWork
import com.local.multiplatformdownloader.feature.creator.DouyinCreatorSource
import com.local.multiplatformdownloader.feature.creator.XiaohongshuCreatorSource
import com.local.multiplatformdownloader.feature.creator.ZhihuCreatorSource
import com.local.multiplatformdownloader.feature.creator.creatorKey
import com.local.multiplatformdownloader.feature.creator.douyinCreatorStableId
import com.local.multiplatformdownloader.feature.creator.preferredXiaohongshuCreatorWorkUrl
import com.local.multiplatformdownloader.feature.creator.xiaohongshuCreatorSearchUrl

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject

class CreatorPlatformSourcesTest {
    @Test
    fun `douyin creator ids support desktop and share homepage urls`() {
        assertEquals("desktop-sec", douyinCreatorStableId("https://www.douyin.com/user/desktop-sec"))
        assertEquals(
            "share-sec",
            douyinCreatorStableId(
                "https://www.iesdouyin.com/share/user/share-sec?sec_uid=share-sec&from_ssr=1",
            ),
        )
        assertEquals(
            "query-sec",
            douyinCreatorStableId("https://www.iesdouyin.com/anything?sec_user_id=query-sec"),
        )
    }

    @Test
    fun `douyin homepage short link resolves before profile request`() {
        val http = FakeParserHttpClient(
            response(
                finalUrl = "https://www.iesdouyin.com/share/user/share-sec?sec_uid=share-sec",
            ),
            response(
                finalUrl = "https://www.douyin.com/aweme/v1/web/user/profile/other/",
                body = """{"user":{"sec_uid":"share-sec","unique_id":"account-1","nickname":"作者"}}""",
            ),
        )

        val profile = DouyinCreatorSource(http).resolve("https://v.douyin.com/homepage/", "session=ok")

        assertEquals("share-sec", profile.stableId)
        assertEquals("account-1", profile.accountId)
        assertEquals(2, http.requests.size)
        assertTrue(http.requests[1].contains("sec_user_id=share-sec"))
    }

    @Test
    fun `douyin account id is rejected because homepage search is not reliable`() {
        try {
            DouyinCreatorSource(FakeParserHttpClient()).resolve("28207782894", "session=ok")
            fail("Expected a homepage link requirement")
        } catch (error: CreatorSourceException) {
            assertEquals("AUTHOR_QUERY_UNSUPPORTED", error.code)
        }
    }

    @Test
    fun `douyin page retries transient rejection and fills profile from work author`() {
        val author = """{
            "sec_uid":"creator-sec",
            "unique_id":"28207782894",
            "nickname":"完整作者",
            "signature":"作者简介",
            "ip_location":"IP属地：广东",
            "follower_count":1234,
            "avatar_larger":{"url_list":["https://example.com/avatar.jpg"]}
        }""".trimIndent()
        val http = FakeParserHttpClient(
            response(statusCode = 403),
            response(body = """{"aweme_list":[{"aweme_id":"1","desc":"作品","author":$author}],"has_more":0}"""),
            response(statusCode = 403),
            response(statusCode = 403),
        )
        val profile = CreatorProfile(
            key = creatorKey(SourcePlatform.DOUYIN, "creator-sec"),
            platform = SourcePlatform.DOUYIN,
            stableId = "creator-sec",
            profileUrl = "https://www.douyin.com/user/creator-sec",
            nickname = "缓存作者",
        )

        val page = DouyinCreatorSource(http).fetchPage(profile, "", 1, "sessionid=ok")

        assertEquals(1, page.works.size)
        assertEquals("完整作者", page.profile.nickname)
        assertEquals("28207782894", page.profile.accountId)
        assertEquals("作者简介", page.profile.bio)
        assertEquals("广东", page.profile.location)
        assertEquals("1234", page.profile.metrics.first { it.label == "粉丝" }.value)
        assertEquals(4, http.requests.size)
        assertTrue(http.requests.take(2).all { "/aweme/post/" in it })
        assertTrue(http.requests.drop(2).all { "/user/profile/other/" in it })
    }

    @Test
    fun `douyin profile refresh keeps cached values when response is partial`() {
        val http = FakeParserHttpClient(
            response(body = """{
                "aweme_list":[{"aweme_id":"1","author":{"sec_uid":"creator-sec","nickname":"作者"}}],
                "has_more":0
            }""".trimIndent()),
            response(body = """{
                "user":{"sec_uid":"creator-sec","nickname":"作者","follower_count":123}
            }""".trimIndent()),
        )
        val profile = CreatorProfile(
            key = creatorKey(SourcePlatform.DOUYIN, "creator-sec"),
            platform = SourcePlatform.DOUYIN,
            stableId = "creator-sec",
            accountId = "cached-id",
            profileUrl = "https://www.douyin.com/user/creator-sec",
            avatarUrl = "https://example.com/cached.jpg",
            nickname = "缓存作者",
            bio = "缓存简介",
            location = "缓存属地",
            metrics = listOf(CreatorMetric("粉丝", "999"), CreatorMetric("关注", "88")),
        )

        val page = DouyinCreatorSource(http).fetchPage(profile, "", 1, "sessionid=ok")

        assertEquals("cached-id", page.profile.accountId)
        assertEquals("https://example.com/cached.jpg", page.profile.avatarUrl)
        assertEquals("缓存简介", page.profile.bio)
        assertEquals("缓存属地", page.profile.location)
        assertEquals(
            listOf(CreatorMetric("粉丝", "123"), CreatorMetric("关注", "88")),
            page.profile.metrics,
        )
    }

    @Test
    fun `xiaohongshu share link uses redirected target instead of logged in user`() {
        val targetId = "62fc76f0000000000f0052b1"
        val http = FakeParserHttpClient(
            response(
                finalUrl = "https://www.xiaohongshu.com/user/profile/$targetId?xsec_source=app_share",
                body = xhsProfileHtml(targetId, "1585863874", "肉肉姨姨er"),
            ),
        )

        val profile = XiaohongshuCreatorSource(http).resolve(
            "https://xhslink.cn/m/9jrZVbzbmsy",
            "web_session=ok",
        )

        assertEquals(targetId, profile.stableId)
        assertEquals("1585863874", profile.accountId)
        assertEquals("肉肉姨姨er", profile.nickname)
    }

    @Test
    fun `xiaohongshu never falls back to unrelated logged in user`() {
        val http = FakeParserHttpClient(
            response(
                finalUrl = "https://www.xiaohongshu.com/user/profile/target-user-id",
                body = xhsProfileHtml("logged-in-user", "self-red-id", "当前登录用户"),
            ),
        )

        try {
            XiaohongshuCreatorSource(http).resolve("https://xhslink.cn/m/target", "web_session=ok")
            fail("Expected exact matching to reject the unrelated user")
        } catch (error: CreatorSourceException) {
            assertEquals("AUTHOR_NOT_FOUND", error.code)
        }
    }

    @Test
    fun `xiaohongshu public account id resolves from browser snapshot only on exact match`() {
        val targetId = "62fc76f0000000000f0052b1"
        val http = FakeParserHttpClient()
        val snapshot = WebPageSnapshot(
            finalUrl = xiaohongshuCreatorSearchUrl("1585863874"),
            initialData = """{"search":{"users":[{"userId":"$targetId","redId":"1585863874","nickname":"肉肉姨姨er"}]}}""",
        )

        val profile = XiaohongshuCreatorSource(http).resolve(
            "1585863874",
            "web_session=ok",
            snapshot,
        )

        assertEquals(targetId, profile.stableId)
        assertEquals("1585863874", profile.accountId)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `xiaohongshu creator works retain xsec token and rendered previews`() {
        val userId = "62fc76f0000000000f0052b1"
        val noteId = "6a7592020000000021023f01"
        val token = "AB+/="
        val avatar = "http://sns-avatar-qc.xhscdn.com/avatar.webp"
        val cover = "http://sns-webpic-qc.xhscdn.com/cover.webp!nc_n_nwebp_mw_1"
        val state = """{"user":{"userPageData":{"basicInfo":{"userId":"$userId","redId":"1585863874","nickname":"肉肉姨姨er","imageb":"$avatar"},"notes":[{"noteCard":{"noteId":"$noteId","displayTitle":"作品","type":"video","xsecToken":"$token","cover":{"urlDefault":"$cover"}}}]}}}"""
        val http = FakeParserHttpClient(response(body = "<script>window.__INITIAL_STATE__=$state;</script>"))
        val profile = CreatorProfile(
            key = creatorKey(SourcePlatform.XIAOHONGSHU, userId),
            platform = SourcePlatform.XIAOHONGSHU,
            stableId = userId,
            profileUrl = "https://www.xiaohongshu.com/user/profile/$userId",
            nickname = "肉肉姨姨er",
        )

        val page = XiaohongshuCreatorSource(http).fetchPage(profile, "", 1, "web_session=ok")

        assertEquals(1, page.works.size)
        assertEquals(
            "https://www.xiaohongshu.com/discovery/item/$noteId" +
                "?source=webshare&xhsshare=pc_web&xsec_token=AB%2B%2F%3D&xsec_source=pc_share",
            page.works.single().canonicalUrl,
        )
        assertEquals("https://sns-webpic-qc.xhscdn.com/cover.webp!nc_n_nwebp_mw_1", page.works.single().coverUrl)
        assertEquals("https://sns-avatar-qc.xhscdn.com/avatar.webp", page.profile.avatarUrl)
    }

    @Test
    fun `xiaohongshu creator works preserve an explicit signed work url`() {
        val userId = "62fc76f0000000000f0052b1"
        val noteId = "6a7592020000000021023f01"
        val explicit = "https://www.xiaohongshu.com/user/profile/$userId/$noteId" +
            "?xsec_token=fresh-token&xsec_source=pc_user"
        val state = """{"user":{"userPageData":{"basicInfo":{"userId":"$userId","nickname":"作者"},"notes":[{"noteCard":{"noteId":"$noteId","type":"video","xsecToken":"fallback","noteUrl":"$explicit"}}]}}}"""
        val source = XiaohongshuCreatorSource(
            FakeParserHttpClient(response(body = "<script>window.__INITIAL_STATE__=$state;</script>")),
        )

        val page = source.fetchPage(xhsProfile(userId), "", 1, "web_session=ok")

        assertEquals(explicit, page.works.single().canonicalUrl)
    }

    @Test
    fun `legacy xiaohongshu creator urls are upgraded without losing token`() {
        val noteId = "6a7592020000000021023f01"
        val legacy = "https://www.xiaohongshu.com/explore/$noteId" +
            "?xsec_token=AB%2B%2F%3D&xsec_source=pc_user"

        assertEquals(
            "https://www.xiaohongshu.com/discovery/item/$noteId" +
                "?source=webshare&xhsshare=pc_web&xsec_token=AB%2B%2F%3D&xsec_source=pc_share",
            preferredXiaohongshuCreatorWorkUrl(noteId, legacy),
        )
    }

    @Test
    fun `xiaohongshu creator empty http state is rejected instead of clearing cache`() {
        val userId = "62fc76f0000000000f0052b1"
        val http = FakeParserHttpClient(
            response(body = xhsProfileHtml(userId, "1585863874", "肉肉姨姨er")),
        )

        try {
            XiaohongshuCreatorSource(http).fetchPage(xhsProfile(userId), "", 1, "web_session=ok")
            fail("Expected an ambiguous empty page to be rejected")
        } catch (error: CreatorSourceException) {
            assertEquals("CREATOR_PAGE_EMPTY", error.code)
        }
    }

    @Test
    fun `xiaohongshu creator page can recover works from browser snapshot`() {
        val userId = "62fc76f0000000000f0052b1"
        val noteId = "6a7592020000000021023f01"
        val state = """{"user":{"userPageData":{"basicInfo":{"userId":"$userId","nickname":"肉肉姨姨er"},"notes":[{"noteCard":{"noteId":"$noteId","displayTitle":"作品","type":"video"}}]}}}"""
        val snapshot = WebPageSnapshot(
            finalUrl = "https://www.xiaohongshu.com/user/profile/$userId",
            initialData = state,
        )

        val page = XiaohongshuCreatorSource(FakeParserHttpClient()).fetchPage(
            xhsProfile(userId),
            "",
            1,
            "web_session=ok",
            snapshot,
        )

        assertEquals(listOf(noteId), page.works.map(CreatorWork::contentId))
    }

    @Test
    fun `zhihu creator page aggregates answers articles pins and videos`() {
        val http = FakeParserHttpClient(
            response(body = zhihuProfileJson()),
            response(body = collection("answer", "1", 4, "question", "10", "回答标题")),
            response(body = collection("article", "2", 3, title = "文章标题")),
            response(body = collection("pin", "3", 2, title = "想法内容")),
            response(body = collection("zvideo", "4", 1, title = "视频标题")),
        )
        val profile = zhihuProfile("MarryMea")

        val page = ZhihuCreatorSource(http).fetchPage(profile, "", 1, "z_c0=ok")

        assertEquals(4, page.works.size)
        assertFalse(page.hasMore)
        assertTrue(page.works.any { it.canonicalUrl.endsWith("/question/10/answer/1") })
        assertTrue(page.works.any { it.canonicalUrl.endsWith("/p/2") })
        assertTrue(page.works.any { it.canonicalUrl.endsWith("/pin/3") })
        assertTrue(page.works.any { it.canonicalUrl.endsWith("/zvideo/4") })
        assertEquals(listOf("answers", "articles", "pins", "zvideos"), http.requests.filter {
            "/MarryMea/" in it
        }.map { request ->
            request.substringAfter("/MarryMea/").substringBefore('?')
        })
        assertTrue(http.requests.none { "/activities" in it })
        assertEquals("衣沾不足惜，但使愿无违。", page.profile.bio)
        assertEquals("辽宁", page.profile.location)
        assertEquals("897729", page.profile.metrics.first { it.label == "粉丝" }.value)
        assertEquals(CreatorAccountStatus.PUBLIC, page.profile.accountStatus)
    }

    @Test
    fun `zhihu profile requests and maps all supported public fields`() {
        val http = FakeParserHttpClient(response(body = zhihuProfileJson()))

        val profile = ZhihuCreatorSource(http).resolve("https://www.zhihu.com/people/MarryMea", "z_c0=ok")

        assertTrue(http.requests.single().contains("follower_count"))
        assertTrue(http.requests.single().contains("pins_count"))
        assertEquals("赵泠", profile.nickname)
        assertEquals("衣沾不足惜，但使愿无违。", profile.bio)
        assertEquals("辽宁", profile.location)
        assertEquals(
            listOf("粉丝", "关注", "获赞", "回答", "文章", "提问", "想法", "专栏"),
            profile.metrics.map(CreatorMetric::label),
        )
    }

    @Test
    fun `zhihu creator page merges collections into twenty newest works`() {
        val http = FakeParserHttpClient(
            response(body = zhihuProfileJson()),
            response(body = collectionRange("answer", 100, 4_000, 20, "question")),
            response(body = collectionRange("article", 200, 3_000, 20)),
            response(body = collectionRange("pin", 300, 2_000, 20)),
            response(body = collectionRange("zvideo", 400, 1_000, 20)),
        )

        val page = ZhihuCreatorSource(http).fetchPage(zhihuProfile("MarryMea"), "", 1, "z_c0=ok")
        val cursor = JSONObject(page.nextCursor)

        assertEquals(20, page.works.size)
        assertTrue(page.works.all { "/answer/" in it.canonicalUrl })
        assertEquals(20, cursor.getInt("answers"))
        assertEquals(0, cursor.getInt("articles"))
        assertEquals(0, cursor.getInt("pins"))
        assertEquals(0, cursor.getInt("zvideos"))
        assertTrue(page.hasMore)
    }

    @Test
    fun `zhihu merged pagination advances only consumed items from each collection`() {
        val firstResponses = listOf(
            response(body = zhihuProfileJson()),
            response(body = collectionRange("answer", 100, 100, 20, "question", createdStep = 4)),
            response(body = collectionRange("article", 200, 99, 20, createdStep = 4)),
            response(body = collectionRange("pin", 300, 98, 20, createdStep = 4)),
            response(body = collectionRange("zvideo", 400, 97, 20, createdStep = 4)),
        )
        val secondResponses = listOf(
            response(body = collectionRange("answer", 105, 80, 20, "question", createdStep = 4)),
            response(body = collectionRange("article", 205, 79, 20, createdStep = 4)),
            response(body = collectionRange("pin", 305, 78, 20, createdStep = 4)),
            response(body = collectionRange("zvideo", 405, 77, 20, createdStep = 4)),
        )
        val http = FakeParserHttpClient(*(firstResponses + secondResponses).toTypedArray())
        val profile = zhihuProfile("MarryMea")

        val first = ZhihuCreatorSource(http).fetchPage(profile, "", 1, "z_c0=ok")
        val firstCursor = JSONObject(first.nextCursor)
        val second = ZhihuCreatorSource(http).fetchPage(profile, first.nextCursor, 2, "z_c0=ok")

        assertEquals(20, first.works.size)
        assertEquals(20, second.works.size)
        assertTrue(listOf("answers", "articles", "pins", "zvideos").all { firstCursor.getInt(it) == 5 })
        assertTrue(first.works.map(CreatorWork::key).toSet().intersect(second.works.map(CreatorWork::key).toSet()).isEmpty())
        assertTrue(http.requests.drop(5).all { "offset=5" in it })
    }

    private class FakeParserHttpClient(
        vararg responses: ParserHttpResponse,
    ) : ParserHttpClient {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<String>()

        override fun get(
            url: String,
            headers: Map<String, String>,
            cookieHeader: String,
            timeoutSeconds: Long,
        ): ParserHttpResponse {
            requests += url
            return responses.removeFirst()
        }

        override fun probeContentLength(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): Long = 0L
    }

    companion object {
        private fun response(
            statusCode: Int = 200,
            finalUrl: String = "https://example.com/",
            body: String = "",
        ) = ParserHttpResponse(statusCode, finalUrl, emptyList(), body, emptyMap())

        private fun xhsProfileHtml(userId: String, redId: String, nickname: String): String =
            """<script>window.__INITIAL_STATE__={"user":{"userPageData":{"basicInfo":{"userId":"$userId","redId":"$redId","nickname":"$nickname"}}}};</script>"""

        private fun collection(
            type: String,
            id: String,
            created: Long,
            nestedName: String = "",
            nestedId: String = "",
            title: String = "",
        ): String {
            val nested = if (nestedName.isBlank()) "" else ",\"$nestedName\":{\"id\":\"$nestedId\",\"title\":\"$title\"}"
            return """{"data":[{"type":"$type","id":"$id","created_time":$created,"created":$created,"title":"$title"$nested}],"paging":{"is_end":true}}"""
        }

        private fun collectionRange(
            type: String,
            idStart: Int,
            createdStart: Long,
            count: Int,
            nestedName: String = "",
            createdStep: Long = 1,
        ): String {
            val data = (0 until count).joinToString(",") { index ->
                val id = idStart + index
                val nested = if (nestedName.isBlank()) "" else {
                    ",\"$nestedName\":{\"id\":\"${9_000 + id}\",\"title\":\"问题 $id\"}"
                }
                val created = createdStart - index * createdStep
                """{"type":"$type","id":"$id","created_time":$created,"created":$created,"title":"作品 $id"$nested}"""
            }
            return """{"data":[$data],"paging":{"is_end":false,"next":"https://www.zhihu.com/api?offset=20"}}"""
        }

        private fun zhihuProfile(token: String) = CreatorProfile(
            key = creatorKey(SourcePlatform.ZHIHU, token),
            platform = SourcePlatform.ZHIHU,
            stableId = token,
            accountId = token,
            profileUrl = "https://www.zhihu.com/people/$token",
            nickname = token,
        )

        private fun zhihuProfileJson(): String = """{
            "url_token":"MarryMea",
            "name":"赵泠",
            "avatar_url":"https://pic.example/avatar.jpg",
            "headline":"衣沾不足惜，但使愿无违。",
            "ip_info":"IP 属地辽宁",
            "follower_count":897729,
            "following_count":20603,
            "voteup_count":6197251,
            "answer_count":20643,
            "articles_count":539,
            "question_count":70,
            "pins_count":4876,
            "columns_count":3,
            "locations":[]
        }""".trimIndent()

        private fun xhsProfile(userId: String) = CreatorProfile(
            key = creatorKey(SourcePlatform.XIAOHONGSHU, userId),
            platform = SourcePlatform.XIAOHONGSHU,
            stableId = userId,
            profileUrl = "https://www.xiaohongshu.com/user/profile/$userId",
            nickname = "肉肉姨姨er",
        )
    }
}
