package com.local.douyindownloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SocialCreatorSourcesTest {
    private val xProfile = CreatorProfile("x:42", SourcePlatform.X, "42", "author", "https://x.com/author", nickname = "作者")
    private val igProfile = CreatorProfile("instagram:42", SourcePlatform.INSTAGRAM, "42", "author", "https://www.instagram.com/author/", nickname = "作者")

    @Test fun `exact usernames and profile links supported`() {
        assertEquals("author", socialCreatorHandle(SourcePlatform.X, "@author"))
        assertEquals("author", socialCreatorHandle(SourcePlatform.X, "https://x.com/author/media"))
        assertEquals("some.name", socialCreatorHandle(SourcePlatform.INSTAGRAM, "https://www.instagram.com/some.name/?igsh=abc"))
        assertEquals("some.name", socialCreatorHandle(SourcePlatform.INSTAGRAM, "@some.name"))
    }

    @Test fun `post paths reserved paths and misleading domains rejected`() {
        listOf("https://x.com/i/status/123", "https://x.com/author/status/123", "https://x.com.evil.test/author",
            "https://author@x.com/author", "home", "a b").forEach { query ->
            assertThrows(CreatorSourceException::class.java) { socialCreatorHandle(SourcePlatform.X, query) }
        }
        listOf("https://www.instagram.com/p/Abc123/", "https://www.instagram.com/accounts/login/", "stories").forEach { query ->
            assertThrows(CreatorSourceException::class.java) { socialCreatorHandle(SourcePlatform.INSTAGRAM, query) }
        }
    }

    @Test fun `new and legacy X profile shapes use stable identity`() {
        val node = JSONObject("""{"rest_id":"42","core":{"screen_name":"author","name":"作者"},"avatar":{"image_url":"https://pbs.twimg.com/test_normal.jpg"},"legacy":{"followers_count":18,"friends_count":5}}""")
        val profile = XCreatorNormalizer.profile(node, "AUTHOR")
        assertEquals("x:42", profile.key)
        assertEquals("作者", profile.nickname)
        assertEquals("18", profile.metrics.first().value)
        assertThrows(CreatorSourceException::class.java) { XCreatorNormalizer.profile(node, "someone_else") }
    }

    private fun xItem(id: String, owner: String = "42", mediaType: String = "photo", repost: Boolean = false): JSONObject {
        val legacy = JSONObject().put("user_id_str", owner).put("full_text", "标题")
        if (mediaType.isNotEmpty()) legacy.put("extended_entities", JSONObject().put("media", JSONArray()
            .put(JSONObject().put("type", mediaType).put("media_url_https", "https://pbs.twimg.com/a.jpg"))))
        if (repost) legacy.put("retweeted_status_result", JSONObject())
        return JSONObject().put("tweet_results", JSONObject().put("result", JSONObject().put("rest_id", id).put("legacy", legacy)))
    }

    private fun xRoot(items: List<JSONObject>, next: String = "next"): JSONObject {
        val entries = JSONArray()
        items.forEach { entries.put(JSONObject().put("content", JSONObject().put("itemContent", it))) }
        if (next.isNotEmpty()) entries.put(JSONObject().put("content", JSONObject().put("cursorType", "Bottom").put("value", next)))
        return JSONObject().put("data", JSONObject().put("user", JSONObject().put("result", JSONObject()
            .put("timeline", JSONObject().put("timeline", JSONObject().put("instructions", JSONArray()
                .put(JSONObject().put("entries", entries))))))))
    }

    @Test fun `X only own media not text reposts or recommendations`() {
        val result = XCreatorNormalizer.page(xRoot(listOf(xItem("101"), xItem("102", mediaType = "video"),
            xItem("103", owner = "99"), xItem("104", mediaType = ""), xItem("105", repost = true), xItem("101"))), xProfile, "", 1)
        assertEquals(listOf("101", "102"), result.works.map { it.contentId })
        assertEquals(MediaKind.VIDEO, result.works[1].kind)
        assertTrue(result.hasMore)
    }

    @Test fun `X empty filtered page keeps bottom cursor and repeated cursor errors`() {
        val root = xRoot(listOf(xItem("101", mediaType = "")))
        assertTrue(XCreatorNormalizer.page(root, xProfile, "", 1).hasMore)
        assertThrows(CreatorSourceException::class.java) { XCreatorNormalizer.page(root, xProfile, "next", 2) }
    }

    @Test fun `X media module format parsed without recursively taking quoted tweets`() {
        val quoted = xItem("101").apply { getJSONObject("tweet_results").getJSONObject("result")
            .put("quoted_status_result", xItem("999", owner = "99").getJSONObject("tweet_results")) }
        val root = xRoot(emptyList())
        val instructions = root.getJSONObject("data").getJSONObject("user").getJSONObject("result")
            .getJSONObject("timeline").getJSONObject("timeline").getJSONArray("instructions")
        instructions.put(JSONObject().put("moduleItems", JSONArray().put(JSONObject().put("item", JSONObject().put("itemContent", quoted)))))
        assertEquals(listOf("101"), XCreatorNormalizer.page(root, xProfile, "", 1).works.map { it.contentId })
    }

    private fun igConnection(nodes: List<JSONObject>, hasMore: Boolean = false, after: String = ""): JSONObject =
        JSONObject().put("edges", JSONArray().apply { nodes.forEach { put(JSONObject().put("node", it)) } })
            .put("page_info", JSONObject().put("has_next_page", hasMore).put("end_cursor", after))

    @Test fun `Instagram carousel containing a video is video and foreign media filtered`() {
        val carousel = JSONObject("""{"code":"ABC123","media_type":8,"user":{"pk":"42"},"carousel_media":[{"media_type":1},{"media_type":2}],"caption":{"text":"图文与视频"}}""")
        val foreign = JSONObject("""{"code":"OTHER","media_type":1,"user":{"pk":"99"}}""")
        val page = InstagramCreatorNormalizer.page(igConnection(listOf(carousel, foreign, carousel)), igProfile, InstagramCreatorCursor(), "", 1)
        assertEquals(1, page.works.size)
        assertEquals(MediaKind.VIDEO, page.works.single().kind)
        assertEquals("图文与视频", page.works.single().title)
        assertEquals("reels", InstagramCreatorCursor.parse(page.nextCursor).phase)
    }

    @Test fun `Instagram opaque pagination remains in correct endpoint phase`() {
        val page = InstagramCreatorNormalizer.page(igConnection(emptyList(), true, "opaque-token"), igProfile, InstagramCreatorCursor(), "", 1)
        assertTrue(page.hasMore)
        assertEquals(InstagramCreatorCursor("posts", "opaque-token"), InstagramCreatorCursor.parse(page.nextCursor))
        val end = InstagramCreatorNormalizer.page(igConnection(emptyList()), igProfile, InstagramCreatorCursor("reels"), "", 2)
        assertFalse(end.hasMore)
    }

    @Test fun `missing Instagram pagination is error not empty success`() {
        assertThrows(CreatorSourceException::class.java) {
            InstagramCreatorNormalizer.page(JSONObject().put("edges", JSONArray()), igProfile, InstagramCreatorCursor(), "", 1)
        }
        assertThrows(CreatorSourceException::class.java) {
            InstagramCreatorNormalizer.page(igConnection(emptyList(), true, "same"), igProfile, InstagramCreatorCursor("posts", "same"), "", 2)
        }
    }

    @Test fun `preparation failures are local failed records without fake files`() {
        val work = CreatorWork("x:101", "x:42", SourcePlatform.X, "101", "https://x.com/i/status/101", MediaKind.VIDEO, "标题")
        assertFalse(work.hasLocalRecord)
        val failed = work.copy(preparation = BatchWorkEntity("b", work.key, "FAILED", "", "详情请求失败"))
        assertTrue(failed.hasLocalRecord)
        assertTrue(failed.preparationFailed)
        assertEquals(CreatorWorkLocalStatus.FAILED, failed.localStatus)
        assertFalse(failed.shouldSkipInSelectAll)
        assertEquals(CreatorWorkLocalStatus.QUEUED, failed.copy(preparation = failed.preparation!!.copy(status = "QUEUED")).localStatus)
    }

    @Test fun `X author requires credentials before making any HTTP request`() {
        val source = XCreatorSource(object : ParserHttpClient {
            override fun get(url: String, headers: Map<String, String>, cookieHeader: String, timeoutSeconds: Long): ParserHttpResponse = error("should not request")
            override fun probeContentLength(url: String, headers: Map<String, String>, timeoutSeconds: Long): Long = error("should not probe")
        })
        val error = assertThrows(CreatorSourceException::class.java) { source.resolve("author", "") }
        assertEquals("LOGIN_REQUIRED", error.code)
    }

    @Test fun `login redirects and throttling not treated as empty list`() {
        fun response(status: Int = 200, url: String = "https://www.instagram.com/api/test", body: String = "{}") =
            ParserHttpResponse(status, url, emptyList(), body, emptyMap())
        assertEquals("LOGIN_REQUIRED", assertThrows(CreatorSourceException::class.java) {
            socialCreatorJson(response(url = "https://www.instagram.com/accounts/login/"), SourcePlatform.INSTAGRAM)
        }.code)
        assertEquals("AUTH_OR_RISK", assertThrows(CreatorSourceException::class.java) {
            socialCreatorJson(response(status = 429), SourcePlatform.INSTAGRAM)
        }.code)
        assertEquals("AUTH_OR_RISK", assertThrows(CreatorSourceException::class.java) {
            socialCreatorJson(response(body = """{"errors":[{"code":88,"message":"secret"}]}"""), SourcePlatform.X)
        }.code)
    }
}
