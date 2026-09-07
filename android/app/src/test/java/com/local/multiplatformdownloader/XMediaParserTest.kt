package com.local.multiplatformdownloader


import com.local.multiplatformdownloader.core.model.AttachmentSelection
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaAttachment
import com.local.multiplatformdownloader.core.model.MediaAttachmentKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.platform.x.ResolvedXPost
import com.local.multiplatformdownloader.platform.x.XMediaNormalizer
import com.local.multiplatformdownloader.platform.x.XPlatformParser
import com.local.multiplatformdownloader.platform.x.XSourceResolver
import com.local.multiplatformdownloader.platform.common.PlatformParseException


import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XMediaParserTest {
    @Test
    fun `platform parser accepts public syndication media`() {
        val payload = """
            {"id_str":"1790637656616943991","text":"video","user":{"name":"A","screen_name":"a"},
             "mediaDetails":[{"id_str":"m","type":"video","media_url_https":"https://pbs.twimg.com/v.jpg",
             "video_info":{"variants":[{"bitrate":832000,"content_type":"video/mp4",
             "url":"https://video.twimg.com/ext_tw_video/1/vid/640x360/v.mp4"}]}}]}
        """.trimIndent()
        val parser = XPlatformParser(QueueHttpClient(ArrayDeque(listOf(response(payload)))))

        val result = parser.parse("https://x.com/a/status/1790637656616943991", "")

        assertTrue(result.ok)
        assertEquals("syndication", result.parserAttempts.single().strategy)
        assertEquals(1, result.attachments.size)
    }

    @Test
    fun `supplied tombstone link reports unavailable after GraphQL confirmation`() {
        val id = "2035348141650870744"
        val http = QueueHttpClient(
            ArrayDeque(
                listOf(
                    response("""{"__typename":"TweetTombstone","tombstone":{}}"""),
                    response("""{"data":{"tweetResult":{"result":{"__typename":"TweetTombstone","tombstone":{}}}}}"""),
                ),
            ),
            postResponses = ArrayDeque(
                listOf(response("""{"guest_token":"guest"}""")),
            ),
        )

        val result = XPlatformParser(http).parse("https://x.com/i/status/$id", "")

        assertFalse(result.ok)
        assertEquals("LOGIN_REQUIRED", result.errorCode)
    }

    @Test
    fun `logged in tombstone is confirmed unavailable`() {
        val tombstone = """{"__typename":"TweetTombstone","tombstone":{}}"""
        val graphQl = """{"data":{"tweetResult":{"result":$tombstone}}}"""
        val parser = XPlatformParser(
            QueueHttpClient(ArrayDeque(listOf(response(tombstone), response(graphQl)))),
        )

        val result = parser.parse(
            "https://x.com/i/status/2035348141650870744",
            "auth_token=session; ct0=csrf",
        )

        assertFalse(result.ok)
        assertEquals("CONTENT_UNAVAILABLE", result.errorCode)
    }

    @Test
    fun `source resolver accepts x and legacy twitter status links`() {
        assertEquals(
            "2035348141650870744",
            XSourceResolver.resolve("https://x.com/i/status/2035348141650870744").id,
        )
        assertEquals(
            "1790637656616943991",
            XSourceResolver.resolve("https://twitter.com/example/status/1790637656616943991").id,
        )
    }

    @Test
    fun `mixed attachments retain order and independent video variants`() {
        val result = XMediaNormalizer.normalize(
            JSONObject(
                """
                {
                  "id_str":"100",
                  "text":"mixed post",
                  "user":{"id_str":"42","name":"Author","screen_name":"author"},
                  "mediaDetails":[
                    {"id_str":"a","type":"photo","media_url_https":"https://pbs.twimg.com/media/a.jpg"},
                    {"id_str":"b","type":"video","media_url_https":"https://pbs.twimg.com/b.jpg","video_info":{"variants":[
                      {"bitrate":832000,"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/1/vid/640x360/b.mp4"},
                      {"bitrate":2176000,"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/1/vid/1280x720/b.mp4"}
                    ]}},
                    {"id_str":"c","type":"animated_gif","media_url_https":"https://pbs.twimg.com/c.jpg","video_info":{"variants":[
                      {"bitrate":0,"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/640x640/c.mp4"}
                    ]}},
                    {"id_str":"d","type":"photo","media_url_https":"https://pbs.twimg.com/media/d.png"}
                  ],
                  "quoted_tweet":{"mediaDetails":[
                    {"id_str":"quoted","type":"photo","media_url_https":"https://pbs.twimg.com/media/quoted.jpg"}
                  ]}
                }
                """.trimIndent(),
            ),
            ResolvedXPost("100", "https://x.com/i/status/100"),
        )

        assertTrue(result.ok)
        assertEquals(SourcePlatform.X, result.platform)
        assertEquals(
            listOf(
                MediaAttachmentKind.IMAGE,
                MediaAttachmentKind.VIDEO,
                MediaAttachmentKind.GIF,
                MediaAttachmentKind.IMAGE,
            ),
            result.attachments.map(MediaAttachment::kind),
        )
        assertEquals(2, result.attachments[1].variants.size)
        assertEquals(1280, result.attachments[1].variants[0].width)
        assertFalse(result.attachments.any { it.id == "quoted" })
        assertTrue(result.attachments[0].imageCandidates.single().endsWith("?name=orig"))
    }

    @Test
    fun `repost uses original post media and author`() {
        val result = XMediaNormalizer.normalize(
            JSONObject(
                """
                {"id_str":"outer","user":{"name":"Reposter"},"retweeted_status":{
                  "id_str":"200","text":"original","user":{"name":"Original","screen_name":"original"},
                  "mediaDetails":[{"id_str":"image","type":"photo","media_url_https":"https://pbs.twimg.com/media/a.jpg"}]
                }}
                """.trimIndent(),
            ),
            ResolvedXPost("outer", "https://x.com/i/status/outer"),
        )

        assertEquals("200", result.contentId)
        assertEquals("Original", result.author)
        assertEquals("https://x.com/original/status/200", result.canonicalUrl)
    }

    @Test
    fun `pure text post is not downloadable`() {
        val error = runCatching {
            XMediaNormalizer.normalize(
                JSONObject("""{"id_str":"300","text":"text only","user":{"name":"A"}}"""),
                ResolvedXPost("300", "https://x.com/i/status/300"),
            )
        }.exceptionOrNull() as PlatformParseException

        assertEquals("MEDIA_EMPTY", error.code)
    }

    @Test
    fun `attachments and selections survive task json round trip`() {
        val attachment = MediaAttachment(
            id = "video",
            index = 0,
            kind = MediaAttachmentKind.VIDEO,
            variants = listOf(
                MediaVariant(1280, 720, 2_000_000, 0, "H.264", 10, "cdn", listOf("https://video.twimg.com/v.mp4")),
            ),
        )
        val spec = TaskSpec(
            taskId = "task",
            createdAt = 1L,
            result = ParseResult(ok = true, platform = SourcePlatform.X, attachments = listOf(attachment)),
            variantIndex = 0,
            attachmentSelections = listOf(AttachmentSelection("video", 0)),
            mode = DownloadMode.MERGE_KEEP,
        )

        val restored = TaskSpec.fromJson(spec.toJson())

        assertEquals(SourcePlatform.X, restored.result.platform)
        assertEquals(attachment, restored.result.attachments.single())
        assertEquals(AttachmentSelection("video", 0), restored.attachmentSelections.single())
    }

    private class QueueHttpClient(
        private val getResponses: ArrayDeque<ParserHttpResponse>,
        private val postResponses: ArrayDeque<ParserHttpResponse> = ArrayDeque(),
    ) : ParserHttpClient {
        override fun get(
            url: String,
            headers: Map<String, String>,
            cookieHeader: String,
            timeoutSeconds: Long,
        ): ParserHttpResponse = getResponses.removeFirst()

        override fun post(
            url: String,
            headers: Map<String, String>,
            cookieHeader: String,
            body: ByteArray,
            timeoutSeconds: Long,
        ): ParserHttpResponse = postResponses.removeFirst()

        override fun probeContentLength(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
        ): Long = 0L
    }

    private fun response(body: String) = ParserHttpResponse(
        statusCode = 200,
        finalUrl = "https://x.com/",
        redirectUrls = emptyList(),
        body = body,
        headers = emptyMap(),
    )
}
