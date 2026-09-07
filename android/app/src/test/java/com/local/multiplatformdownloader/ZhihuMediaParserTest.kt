package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.DocumentBlock
import com.local.multiplatformdownloader.core.model.DocumentBlockType
import com.local.multiplatformdownloader.core.model.DocumentType
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.platform.zhihu.ZhihuMediaParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuRichContentParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuSourceResolver


import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhihuMediaParserTest {
    @Test
    fun standaloneVideoDeduplicatesLogicalQualitiesAndNormalizesKbps() {
        val payload = JSONObject(
            """
            {
              "id":"2035289178502645430",
              "title":"盘点视频",
              "author":{"name":"作者"},
              "image_url":"https://pic1.zhimg.com/cover_r.jpg",
              "video":{"playlist":{
                "ld":{"play_url":"https://vdn3.vzuu.com/video.mp4?token=a","width":848,"height":636,"fps":30,"bitrate":677,"size":27521036,"format":"mp4","channels":2},
                "sd":{"url":"https://vdn3.vzuu.com/video.mp4?token=a","width":848,"height":636,"fps":30,"bitrate":677,"size":27521036,"format":"mp4","channels":2}
              }}
            }
            """.trimIndent(),
        )

        val result = ZhihuMediaParser.normalizeStandaloneVideo(
            payload,
            "2035289178502645430",
            "https://www.zhihu.com/zvideo/2035289178502645430",
        )

        assertEquals(MediaKind.VIDEO, result.kind)
        assertEquals("作者", result.author)
        assertEquals(1, result.variants.size)
        assertEquals(677_000, result.variants.single().bitrate)
        assertEquals(27_521_036L, result.variants.single().size)
    }

    @Test
    fun standaloneVideoReadsAuthorFromNestedVideoOrCreatorShapes() {
        val nestedAuthor = JSONObject(
            """
            {
              "title":"嵌套作者视频",
              "video":{
                "author":{"nickname":"嵌套作者"},
                "playlist":{"hd":{"play_url":"https://vdn.vzuu.com/a.mp4"}}
              }
            }
            """.trimIndent(),
        )
        val creator = JSONObject(
            """
            {
              "title":"创作者字段视频",
              "creator":{"name":"创作者"},
              "video":{"playlist":{"hd":{"play_url":"https://vdn.vzuu.com/b.mp4"}}}
            }
            """.trimIndent(),
        )

        assertEquals(
            "嵌套作者",
            ZhihuMediaParser.normalizeStandaloneVideo(nestedAuthor, "1", "https://www.zhihu.com/zvideo/1").author,
        )
        assertEquals(
            "创作者",
            ZhihuMediaParser.normalizeStandaloneVideo(creator, "2", "https://www.zhihu.com/zvideo/2").author,
        )
    }

    @Test
    fun richContentPreservesTextImageVideoOrderAndFiltersUnsafeContent() {
        val parsed = ZhihuRichContentParser.parse(
            """
            <p>开头 <strong>加粗</strong><script>alert(1)</script></p>
            <figure><img data-original="https://picx.zhimg.com/photo_r.jpg" alt="图一"></figure>
            <blockquote>引用内容</blockquote>
            <a class="video-box" data-lens-id="1793672973791678464" data-name="演示视频">
              <img src="https://picx.zhimg.com/video-cover.jpg">
            </a>
            <p><a href="javascript:alert(1)">危险链接</a>结尾</p>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                DocumentBlockType.PARAGRAPH,
                DocumentBlockType.IMAGE,
                DocumentBlockType.BLOCKQUOTE,
                DocumentBlockType.VIDEO,
                DocumentBlockType.PARAGRAPH,
            ),
            parsed.blocks.map(DocumentBlock::type),
        )
        assertEquals("开头 **加粗**", parsed.blocks[0].text)
        assertFalse(parsed.blocks.last().text.contains("javascript:"))
        assertEquals(2, parsed.assets.size)
        assertEquals("https://picx.zhimg.com/photo_r.jpg", parsed.assets[0].candidateUrls.single())
        assertEquals("1793672973791678464", parsed.assets[1].sourceId)
        assertEquals(listOf("https://picx.zhimg.com/video-cover.jpg"), parsed.assets[1].coverUrls)
    }

    @Test
    fun answerBecomesDocumentAndHydratesInlineVideo() {
        val source = ZhihuSourceResolver.resolve(
            "https://www.zhihu.com/question/26730775/answer/2079127079271011205",
        )
        val payload = JSONObject(
            """
            {
              "id":"2079127079271011205",
              "question":{"title":"问题标题"},
              "author":{"name":"答主"},
              "content":"<p>正文</p><a class='video-box' data-lens-id='1793672973791678464'></a>"
            }
            """.trimIndent(),
        )
        val videoPayload = JSONObject(
            """
            {"playlist":{"FHD":{"play_url":"https://vdn.vzuu.com/fhd.mp4","width":1920,"height":1080,"fps":30,"bitrate":2200,"size":50000000}}}
            """.trimIndent(),
        )

        val result = ZhihuMediaParser.normalizeDocument(payload, source) { videoPayload }

        assertEquals(MediaKind.DOCUMENT, result.kind)
        assertEquals(DocumentType.ANSWER, result.document?.type)
        assertEquals("问题标题", result.description)
        assertEquals("答主", result.author)
        assertEquals(2_200_000, result.document?.assets?.single()?.variants?.single()?.bitrate)
    }

    @Test
    fun externalVideoIsKeptAsAPlainSafeLink() {
        val parsed = ZhihuRichContentParser.parse(
            "<p>外链 <a href='https://www.bilibili.com/video/BV1xx'>哔哩哔哩视频</a>" +
                "<iframe src='https://player.example.com/video/1'></iframe></p>",
        )

        assertTrue(parsed.blocks.single().text.contains("[哔哩哔哩视频]"))
        assertTrue(parsed.blocks.single().text.contains("https://www.bilibili.com/video/BV1xx"))
        assertTrue(parsed.blocks.single().text.contains("[外部视频](https://player.example.com/video/1)"))
        assertTrue(parsed.assets.isEmpty())
    }

    @Test
    fun pinStructuredContentBecomesOrderedDocument() {
        val source = ZhihuSourceResolver.resolve("https://www.zhihu.com/pin/2078000000000000000")
        val payload = JSONObject(
            """
            {
              "id":"2078000000000000000",
              "title":"想法标题",
              "author":{"name":"作者"},
              "content":[
                {"type":"text","content":"第一段"},
                {"type":"image","url":"https://picx.zhimg.com/pin.jpg"},
                {"type":"text","content":"第二段"}
              ]
            }
            """.trimIndent(),
        )

        val result = ZhihuMediaParser.normalizeDocument(payload, source)

        assertEquals(DocumentType.PIN, result.document?.type)
        assertEquals(
            listOf(DocumentBlockType.PARAGRAPH, DocumentBlockType.IMAGE, DocumentBlockType.PARAGRAPH),
            result.document?.blocks?.map(DocumentBlock::type),
        )
    }
}
