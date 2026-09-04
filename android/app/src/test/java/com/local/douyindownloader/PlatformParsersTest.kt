package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformParsersTest {
    @Test
    fun `douyin parser resolves link and returns all media fields`() {
        val http = FakeParserHttpClient(
            responses = ArrayDeque(
                listOf(
                    response(finalUrl = "https://www.douyin.com/video/7670091606150329338"),
                    response(
                        body = """
                        {"status_code":0,"aweme_detail":{
                          "aweme_id":"7670091606150329338","desc":"作品","author":{"nickname":"作者"},
                          "video":{
                            "bit_rate":[{"bit_rate":4000000,"play_addr":{"width":1080,"height":1920,"url_list":["https://cdn.example/video.mp4"]}}],
                            "bit_rate_audio":[{"audio_meta":{"bitrate":192000,"url_list":["https://cdn.example/audio.m4a"]}}]
                          }
                        }}
                        """.trimIndent(),
                    ),
                ),
            ),
            probeSizes = mapOf("https://cdn.example/video.mp4" to 9_000_000L),
        )

        val result = DouyinPlatformParser(http).parse(
            "复制 https://v.douyin.com/example/ 打开抖音",
            "ttwid=value",
        )

        assertTrue(result.ok)
        assertEquals("7670091606150329338", result.contentId)
        assertEquals(1, result.variants.size)
        assertEquals(9_000_000L, result.variants[0].size)
        assertEquals("cdn", result.variants[0].sizeSource)
        assertEquals(listOf("https://cdn.example/audio.m4a"), result.audioUrls)
        assertTrue(http.requests.last().url.contains("a_bogus="))
        assertEquals("ttwid=value", http.requests.last().cookie)
    }

    @Test
    fun `xiaohongshu parser binds redirected target and probes size`() {
        val noteId = "64abc123"
        val page = """
            <script>window.__INITIAL_STATE__={"note":{"noteDetailMap":{"$noteId":{"note":{
              "noteId":"$noteId","type":"video","title":"标题",
              "video":{"media":{"stream":{"h264":[{"masterUrl":"https://sns-video-bd.xhscdn.com/video.mp4","width":1080,"height":1920}]}}}
            }}}}};</script>
        """.trimIndent()
        val http = FakeParserHttpClient(
            responses = ArrayDeque(
                listOf(
                    response(
                        finalUrl = "https://www.xiaohongshu.com/explore/$noteId",
                        body = page,
                    ),
                ),
            ),
            probeSizes = mapOf("https://sns-video-bd.xhscdn.com/video.mp4" to 12_000_000L),
        )

        val result = XiaohongshuPlatformParser(http).parse("复制 https://xhslink.cn/example", "")

        assertTrue(result.ok)
        assertEquals(noteId, result.contentId)
        assertEquals("标题", result.description)
        assertEquals(12_000_000L, result.variants.single().size)
        assertEquals("cdn", result.variants.single().sizeSource)
    }

    @Test
    fun `platform errors retain stable codes`() {
        val http = FakeParserHttpClient(
            responses = ArrayDeque(
                listOf(
                    response(finalUrl = "https://www.douyin.com/video/7670091606150329338"),
                    response(statusCode = 403),
                ),
            ),
        )

        val result = DouyinPlatformParser(http).parse(
            "https://www.douyin.com/video/7670091606150329338",
            "",
        )

        assertFalse(result.ok)
        assertEquals("AUTH_OR_RISK", result.errorCode)
    }

    @Test
    fun `zhihu answer falls back from api to exact page state`() {
        val answerId = "2079127079271011205"
        val page = """
            <script id="js-initialData" type="application/json">
            {"initialState":{"entities":{"answers":{"$answerId":{
              "id":"$answerId","question":{"title":"问题"},"author":{"name":"作者"},
              "content":"<p>正文</p><img data-original='https://picx.zhimg.com/a.jpg'>"
            }}}}}
            </script>
        """.trimIndent()
        val http = FakeParserHttpClient(
            responses = ArrayDeque(
                listOf(
                    response(statusCode = 403, finalUrl = "https://www.zhihu.com/api/v4/answers/$answerId"),
                    response(
                        finalUrl = "https://www.zhihu.com/question/26730775/answer/$answerId",
                        body = page,
                    ),
                ),
            ),
        )

        val result = ZhihuPlatformParser(http).parse(
            "https://www.zhihu.com/question/26730775/answer/$answerId",
            "d_c0=cookie",
        )

        assertTrue(result.ok)
        assertEquals(MediaKind.DOCUMENT, result.kind)
        assertEquals(1, result.document?.assets?.size)
        assertEquals(listOf("d_c0=cookie", "d_c0=cookie"), http.requests.map { it.cookie })
    }

    @Test
    fun `zhihu risk error is retained when api and page are rejected`() {
        val http = FakeParserHttpClient(
            responses = ArrayDeque(
                listOf(
                    response(statusCode = 403),
                    response(statusCode = 403),
                ),
            ),
        )

        val result = ZhihuPlatformParser(http).parse(
            "https://www.zhihu.com/question/1/answer/2079127079271011205",
            "",
        )

        assertFalse(result.ok)
        assertEquals("AUTH_OR_RISK", result.errorCode)
    }

    @Test
    fun `zhihu standalone video probes missing exact size`() {
        val videoId = "2035289178502645430"
        val mediaUrl = "https://vdn.vzuu.com/video.mp4"
        val http = FakeParserHttpClient(
            responses = ArrayDeque(
                listOf(
                    response(
                        finalUrl = "https://api.zhihu.com/zvideos/$videoId",
                        body = """{"title":"视频","video":{"playlist":{"hd":{"play_url":"$mediaUrl","width":1920,"height":1080}}}}""",
                    ),
                ),
            ),
            probeSizes = mapOf(mediaUrl to 88_000_000L),
        )

        val result = ZhihuPlatformParser(http).parse("https://www.zhihu.com/zvideo/$videoId", "")

        assertTrue(result.ok)
        assertEquals(88_000_000L, result.variants.single().size)
        assertEquals("cdn", result.variants.single().sizeSource)
    }

    private data class CapturedRequest(val url: String, val cookie: String)

    private class FakeParserHttpClient(
        private val responses: ArrayDeque<ParserHttpResponse>,
        private val probeSizes: Map<String, Long> = emptyMap(),
    ) : ParserHttpClient {
        val requests = mutableListOf<CapturedRequest>()

        override fun get(
            url: String,
            headers: Map<String, String>,
            cookieHeader: String,
            timeoutSeconds: Long,
        ): ParserHttpResponse {
            requests += CapturedRequest(url, cookieHeader)
            return responses.removeFirst()
        }

        override fun probeContentLength(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): Long = probeSizes[url] ?: 0L
    }

    companion object {
        private fun response(
            statusCode: Int = 200,
            finalUrl: String = "https://www.douyin.com/",
            body: String = "",
        ) = ParserHttpResponse(statusCode, finalUrl, emptyList(), body, emptyMap())
    }
}
