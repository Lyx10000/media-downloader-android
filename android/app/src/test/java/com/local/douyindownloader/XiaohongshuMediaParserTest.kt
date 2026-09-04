package com.local.douyindownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuMediaParserTest {
    @Test
    fun `recognizes links and only upgrades trusted CDN hosts`() {
        assertTrue(XiaohongshuMediaParser.isShare("复制 https://xhslink.cn/AbCdEf 打开小红书"))
        assertFalse(XiaohongshuMediaParser.isShare("https://evilxiaohongshu.com/explore/abc"))
        assertEquals(
            "https://sns-video-v2.xhscdn.com/video.mp4?token=abc",
            XiaohongshuMediaParser.secureCdnUrl("http://sns-video-v2.xhscdn.com/video.mp4?token=abc"),
        )
        assertEquals(
            "http://xhscdn.com.evil.example/video.mp4",
            XiaohongshuMediaParser.secureCdnUrl("http://xhscdn.com.evil.example/video.mp4"),
        )
    }

    @Test
    fun `extracts initial state without replacing text inside strings`() {
        val page = """
            <script>window.__INITIAL_STATE__={"note":{"text":"{undefined}","missing":undefined}};</script>
        """.trimIndent()

        val state = XiaohongshuMediaParser.extractInitialState(page)!!

        assertEquals("{undefined}", state.getJSONObject("note").getString("text"))
        assertTrue(state.getJSONObject("note").isNull("missing"))
    }

    @Test
    fun `binds only requested note`() {
        val state = JSONObject(
            """
            {"note":{"noteDetailMap":{
              "wanted":{"note":{"noteId":"wanted","title":"目标"}},
              "recommended":{"note":{"noteId":"recommended","title":"推荐"}}
            }}}
            """.trimIndent(),
        )

        assertEquals("目标", XiaohongshuMediaParser.findTargetNote(state, "wanted")!!.getString("title"))
        assertNull(XiaohongshuMediaParser.findTargetNote(state, "absent"))
    }

    @Test
    fun `restores all original image CDN candidates`() {
        val candidates = XiaohongshuMediaParser.imageCandidates(
            JSONObject().put(
                "urlDefault",
                "https://sns-img-qc.xhscdn.com/1040g008/spectrum/abc123!nd_dft_wlteh_webp_3",
            ),
        )

        assertEquals("https://sns-img-bd.xhscdn.com/spectrum/abc123", candidates[0])
        assertTrue(candidates.contains("https://sns-img-qn.xhscdn.com/spectrum/abc123"))
        assertTrue(candidates.last().endsWith("!nd_dft_wlteh_webp_3"))
    }

    @Test
    fun `rejects segmented streams and sorts equal quality H264 first`() {
        assertFalse(XiaohongshuMediaParser.isDirectVideoUrl("https://sns-video-bd.xhscdn.com/a/master.m3u8"))
        assertFalse(XiaohongshuMediaParser.isDirectVideoUrl("https://sns-video-bd.xhscdn.com/hls/a.mp4"))
        assertTrue(XiaohongshuMediaParser.isDirectVideoUrl("https://sns-video-bd.xhscdn.com/a/video.mp4"))
        val note = JSONObject(
            """
            {"video":{"media":{"stream":{
              "h265":[{"masterUrl":"https://sns-video-bd.xhscdn.com/4k-hevc.mp4","width":2160,"height":3840,"fps":60,"avgBitrate":8000000,"size":80000000}],
              "h264":[{"masterUrl":"http://sns-video-bd.xhscdn.com/4k-avc.mp4","width":2160,"height":3840,"fps":60,"avgBitrate":8000000,"size":80000000},{"masterUrl":"https://sns-video-bd.xhscdn.com/1080.mp4","width":1080,"height":1920}]
            }}}}
            """.trimIndent(),
        )

        val variants = XiaohongshuMediaParser.extractVideoVariants(note)

        assertEquals("H.264", variants[0].codec)
        assertTrue(variants[0].urls[0].startsWith("https://"))
        assertEquals(1080, variants.last().width)
    }

    @Test
    fun `normalizes image and origin video notes`() {
        val image = XiaohongshuMediaParser.normalizeNote(
            JSONObject(
                """{"noteId":"image-note","type":"normal","title":"图片标题","user":{"nickname":"作者"},"imageList":[{"urlDefault":"https://sns-img-qc.xhscdn.com/spectrum/image-one!webp"}]}""",
            ),
            "image-note",
            "https://www.xiaohongshu.com/explore/image-note",
        )
        val video = XiaohongshuMediaParser.normalizeNote(
            JSONObject(
                """{"noteId":"video-note","type":"video","video":{"consumer":{"originVideoKey":"origin/video-file"}}}""",
            ),
            "video-note",
            "https://www.xiaohongshu.com/explore/video-note",
        )

        assertEquals(MediaKind.IMAGE, image.kind)
        assertEquals("作者", image.author)
        assertEquals(MediaKind.VIDEO, video.kind)
        assertTrue(video.variants[0].urls[0].startsWith("https://sns-video-bd.xhscdn.com/"))
    }
}
