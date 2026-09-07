package com.local.multiplatformdownloader


import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.extractSupportedSource
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.feature.creator.CREATOR_BATCH_PLATFORMS
import com.local.multiplatformdownloader.feature.home.loginEnvironmentStartUrl
import com.local.multiplatformdownloader.feature.home.shouldRefreshCookieEnvironment
import com.local.multiplatformdownloader.platform.bilibili.BilibiliMediaParser
import com.local.multiplatformdownloader.platform.bilibili.BilibiliParseException
import com.local.multiplatformdownloader.platform.bilibili.BilibiliPlatformParser
import com.local.multiplatformdownloader.platform.bilibili.BilibiliSource
import com.local.multiplatformdownloader.platform.bilibili.BilibiliSourceResolver
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
import com.local.multiplatformdownloader.platform.common.detectPlatformCredential
import com.local.multiplatformdownloader.core.network.responseShape


import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BilibiliMediaParserTest {
    private val bv = "BV1vobn6dEAR"

    @Test fun `bare IDs and share links preserve case and old platforms`() {
        assertEquals("https://www.bilibili.com/video/$bv", extractSupportedSource(bv)?.url)
        assertEquals(SourcePlatform.BILIBILI, extractSupportedSource("av170001")?.platform)
        assertEquals(SourcePlatform.BILIBILI, extractSupportedSource("分享 https://b23.tv/abc。")?.platform)
        assertNull(extractSupportedSource("prefix $bv suffix"))
        assertNull(extractSupportedSource("https://bilibili.com.evil.test/video/$bv"))
        assertEquals(SourcePlatform.DOUYIN, extractSupportedSource("https://v.douyin.com/abc")?.platform)
        assertEquals(SourcePlatform.XIAOHONGSHU, extractSupportedSource("https://xhslink.cn/abc")?.platform)
        assertEquals(SourcePlatform.ZHIHU, extractSupportedSource("https://www.zhihu.com/zvideo/1")?.platform)
        assertEquals(SourcePlatform.X, extractSupportedSource("https://x.com/i/status/1")?.platform)
        assertEquals(SourcePlatform.INSTAGRAM, extractSupportedSource("https://www.instagram.com/p/abc/")?.platform)
    }

    @Test fun `part selection defaults to one and ignores tracking parameters`() {
        assertEquals(BilibiliSource(bv, 1), BilibiliSourceResolver.resolve("https://www.bilibili.com/video/$bv/"))
        assertEquals(BilibiliSource(bv, 2), BilibiliSourceResolver.resolve("https://m.bilibili.com/video/$bv?share=abc&p=2&t=7"))
        for (suffix in listOf("p=0", "p=-1", "p=2&p=1", "p=", "p=999999999999")) {
            assertCode("INVALID_PART") { BilibiliSourceResolver.resolve("https://www.bilibili.com/video/$bv?$suffix") }
        }
        for (url in listOf("https://www.bilibili.com/bangumi/play/ep1", "https://space.bilibili.com/1", "https://live.bilibili.com/1")) {
            assertCode("UNSUPPORTED_CONTENT") { BilibiliSourceResolver.resolve(url) }
        }
        assertCode("UNSUPPORTED_URL") { BilibiliSourceResolver.resolve("https://user@www.bilibili.com/video/$bv") }
    }

    @Test fun `normalizer selects the requested cid and uses separate candidates for one AAC track`() {
        val part = BilibiliMediaParser.part(detail(), BilibiliSource(bv, 2))
        val result = BilibiliMediaParser.normalize(part, play())
        assertEquals("$bv:200", result.contentId)
        assertEquals("https://www.bilibili.com/video/$bv?p=2", result.canonicalUrl)
        assertTrue(result.description.contains("P2 第二集"))
        assertEquals("作者", result.author)
        assertEquals("99", result.authorStableId)
        assertEquals(SourcePlatform.BILIBILI, result.platform)
        assertEquals(2, result.variants.size)
        assertEquals(listOf(1080, 720), result.variants.map { it.height })
        assertEquals(29, result.variants.first().fps)
        assertEquals(listOf("https://a.bilivideo.com/v1080", "https://b.bilivideo.com/v1080"), result.variants.first().urls)
        assertEquals(listOf("https://a.bilivideo.com/a192", "https://b.bilivideo.com/a192"), result.audioUrls)
        assertFalse(result.audioUrls.any { it.contains("a64") })
        assertEquals("{}", result.rawJson)
        assertFalse(result.responseShape.contains("https://"))
        val restored = ParseResult.fromJson(result.toJson().toString())
        assertEquals(result.contentId, restored.contentId)
        assertEquals(result.canonicalUrl, restored.canonicalUrl)
        assertEquals(result.audioUrls, restored.audioUrls)
    }

    @Test fun `metadata refuses mismatched unavailable paid and interactive content`() {
        assertCode("CONTENT_MISMATCH") { BilibiliMediaParser.part(detail(), BilibiliSource("BV1Z6t96QEG4", 1)) }
        assertCode("INVALID_PART") { BilibiliMediaParser.part(detail(), BilibiliSource(bv, 3)) }
        for (key in listOf("pay", "ugc_pay", "arc_pay", "ugc_pay_preview", "movie", "is_stein_gate")) {
            val data = detail().put("rights", JSONObject().put(key, 1))
            assertCode("UNSUPPORTED_CONTENT") { BilibiliMediaParser.part(data, BilibiliSource(bv, 1)) }
        }
        assertCode("PERMISSION_DENIED") {
            BilibiliMediaParser.part(detail().put("rights", JSONObject().put("download", 0)), BilibiliSource(bv, 1))
        }
        assertCode("UNSUPPORTED_CONTENT") {
            BilibiliMediaParser.part(detail().put("redirect_url", "/bangumi/play/ep1"), BilibiliSource(bv, 1))
        }
    }

    @Test fun `DASH without audio cannot claim a merged result`() {
        val data = play()
        data.getJSONObject("dash").remove("audio")
        assertCode("AUDIO_UNAVAILABLE") { BilibiliMediaParser.normalize(part(), data) }
    }

    @Test fun `durl segments are never mistaken for alternate CDN URLs`() {
        val data = JSONObject().put("durl", JSONArray().put(JSONObject().put("url", "https://a.bilivideo.com/seg1"))
            .put(JSONObject().put("url", "https://a.bilivideo.com/seg2")))
        assertCode("SEGMENTED_MEDIA_UNSUPPORTED") { BilibiliMediaParser.normalize(part(), data) }
    }

    @Test fun `only actual compatible tracks are shown and no unsupported format is substituted`() {
        val data = play()
        data.getJSONObject("dash").put("video", JSONArray().put(track("hevc", "hev1.1", "video/mp4", 1080, 1000)))
        assertCode("FORMAT_UNSUPPORTED") { BilibiliMediaParser.normalize(part(), data) }
        val missingInit = play()
        missingInit.getJSONObject("dash").getJSONArray("video").let { tracks ->
            for (i in 0 until tracks.length()) tracks.getJSONObject(i).remove("SegmentBase")
        }
        assertCode("FORMAT_UNSUPPORTED") { BilibiliMediaParser.normalize(part(), missingInit) }
        assertCode("DRM_UNSUPPORTED") { BilibiliMediaParser.normalize(part(), play().put("drm_tech_type", 2)) }
        assertCode("PERMISSION_DENIED") { BilibiliMediaParser.normalize(part(), play().put("is_preview", 1)) }
        assertCode("CONTENT_MISMATCH") { BilibiliMediaParser.normalize(part(), play().put("cid", "wrong")) }
    }

    @Test fun `metadata resolves large av IDs without local BV conversion`() {
        val aid = "114868162141203"
        assertEquals("200", BilibiliMediaParser.part(detail().put("aid", aid), BilibiliSource("av$aid", 2)).cid)
    }

    @Test fun `shortlink is anonymous and only selected page reaches playurl`() {
        val http = FakeHttp(ArrayDeque(listOf(
            response(302, headers = mapOf("Location" to "https://www.bilibili.com/video/$bv?p=2")),
            response(body = envelope(detail())), response(body = envelope(play())),
        )))
        val result = BilibiliPlatformParser(http).parse("https://b23.tv/example", "SESSDATA=fixture", null)
        assertTrue(result.message, result.ok)
        assertEquals("", http.requests[0].second)
        assertEquals("SESSDATA=fixture", http.requests[1].second)
        assertTrue(http.requests[2].first.contains("cid=200"))
        assertEquals(3, http.requests.size)
    }

    @Test fun `shortlink rejects outside redirect before any further request`() {
        for (target in listOf("https://evil.test/video/$bv", "http://127.0.0.1/", "https://www.bilibili.com/bangumi/play/ep1")) {
            val http = FakeHttp(ArrayDeque(listOf(response(302, headers = mapOf("Location" to target)))))
            assertFalse(BilibiliPlatformParser(http).parse("https://b23.tv/example", "SESSDATA=fixture", null).ok)
            assertEquals(1, http.requests.size)
            assertEquals("", http.requests.single().second)
        }
    }

    @Test fun `risk stops without fallback and server body is not exposed`() {
        val http = FakeHttp(ArrayDeque(listOf(response(body = """{"code":-352,"message":"SESSDATA=secret"}"""))))
        val result = BilibiliPlatformParser(http).parse(bv, "", null)
        assertEquals("BILIBILI_RISK", result.errorCode)
        assertEquals(1, http.requests.size)
        assertFalse(result.toJson().toString().contains("secret"))
        assertFalse(shouldRefreshCookieEnvironment("LOGIN_REQUIRED", false, SourcePlatform.BILIBILI))
    }

    @Test fun `credential existence is checked on server and expiry is explicit`() {
        assertEquals(PlatformCredentialState.DETECTED, detectPlatformCredential(SourcePlatform.BILIBILI, "SESSDATA=fixture"))
        val http = FakeHttp(ArrayDeque(listOf(response(body = envelope(JSONObject().put("isLogin", true))),
            response(body = """{"code":-101}"""))))
        val parser = BilibiliPlatformParser(http)
        assertEquals(PlatformCredentialState.DETECTED, parser.credentialState("SESSDATA=fixture"))
        assertEquals(PlatformCredentialState.EXPIRED, parser.credentialState("SESSDATA=fixture"))
        assertEquals(PlatformCredentialState.NOT_DETECTED, parser.credentialState(""))
        assertEquals(2, http.requests.size)
        assertTrue(CREATOR_BATCH_PLATFORMS.contains(SourcePlatform.BILIBILI))
        assertEquals("https://passport.bilibili.com/h5-app/passport/login",
            loginEnvironmentStartUrl(SourcePlatform.BILIBILI, PlatformCredentialState.NOT_DETECTED))
    }

    @Test fun `CDN allowlist rejects credential addresses and lookalikes and redactor covers bili tokens`() {
        assertNull(BilibiliMediaParser.mediaUrl("https://bilivideo.com.evil.test/v"))
        assertNull(BilibiliMediaParser.mediaUrl("https://user@a.bilivideo.com/v"))
        assertNull(BilibiliMediaParser.mediaUrl("https://127.0.0.1/v"))
        assertEquals("https://a.bilivideo.com/v?x=y", BilibiliMediaParser.mediaUrl("http://a.bilivideo.com/v?x=y"))
        assertFalse(Redactor.sanitize("SESSDATA=sentinel;bili_jct=sentinel;buvid3=sentinel;w_rid=sentinel").contains("sentinel"))
    }

    private fun part() = BilibiliMediaParser.part(detail(), BilibiliSource(bv, 1))
    private fun detail() = JSONObject("""{"bvid":"$bv","aid":170001,"title":"标题","pic":"https://i.hdslb.com/cover.jpg",
        "owner":{"mid":99,"name":"作者"},"rights":{"download":1},"pages":[
        {"page":1,"cid":100,"part":"第一集","duration":20},{"page":2,"cid":200,"part":"第二集","duration":30}]}""")
    private fun play() = JSONObject().put("support_formats", JSONArray().put(JSONObject().put("quality", 120)))
        .put("dash", JSONObject().put("duration", 20).put("video", JSONArray()
            .put(track("v720", "avc1.64001F", "video/mp4", 720, 1000000))
            .put(track("v1080", "avc1.640028", "video/mp4", 1080, 2000000))
            .put(track("v4k", "hev1.1", "video/mp4", 2160, 3000000)))
            .put("audio", JSONArray().put(track("a64", "mp4a.40.2", "audio/mp4", 0, 64000))
                .put(track("a192", "mp4a.40.2", "audio/mp4", 0, 192000))))
    private fun track(name: String, codec: String, mime: String, height: Int, rate: Int) = JSONObject()
        .put("baseUrl", "https://a.bilivideo.com/$name").put("backupUrl", JSONArray().put("https://b.bilivideo.com/$name"))
        .put("codecs", codec).put("mimeType", mime).put("height", height).put("width", height * 16 / 9)
        .put("bandwidth", rate).put("frameRate", "30000/1001")
        .put("SegmentBase", JSONObject().put("Initialization", "0-999").put("indexRange", "1000-1999"))
    private fun envelope(data: JSONObject) = JSONObject().put("code", 0).put("data", data).toString()
    private fun assertCode(code: String, action: () -> Unit) {
        assertEquals(code, assertThrows(BilibiliParseException::class.java) { action() }.code)
    }
    private fun response(status: Int = 200, body: String = "", headers: Map<String, String> = emptyMap()) =
        ParserHttpResponse(status, "", emptyList(), body, headers)
    private class FakeHttp(val responses: ArrayDeque<ParserHttpResponse>) : ParserHttpClient {
        val requests = mutableListOf<Pair<String, String>>()
        override fun getWithoutRedirects(url: String, headers: Map<String, String>, cookieHeader: String, timeoutSeconds: Long): ParserHttpResponse {
            requests += url to cookieHeader
            return responses.removeFirst()
        }
        override fun get(url: String, headers: Map<String, String>, cookieHeader: String, timeoutSeconds: Long): ParserHttpResponse = error("Unsafe redirecting client")
        override fun probeContentLength(url: String, headers: Map<String, String>, timeoutSeconds: Long): Long = error("Unexpected probe")
    }
}
