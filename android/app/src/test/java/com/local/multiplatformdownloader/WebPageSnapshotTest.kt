package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.platform.zhihu.ZhihuWebSnapshotExtractor
import com.local.multiplatformdownloader.platform.common.PlatformParseException
import com.local.multiplatformdownloader.platform.zhihu.ZhihuMediaParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuSourceResolver


import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageSnapshotTest {
    @Test
    fun decodesEvaluateJavascriptStringResult() {
        val payload = JSONObject().apply {
            put("finalUrl", "https://www.zhihu.com/question/1/answer/2")
            put("initialData", "{\"initialState\":{}}")
            put("title", "标题")
            put("author", "作者")
            put("contentHtml", "<p>正文</p>")
            put("visibleText", "正文")
        }.toString()

        val snapshot = WebPageSnapshot.fromJavascriptResult(JSONObject.quote(payload))

        assertEquals("标题", snapshot?.title)
        assertEquals("<p>正文</p>", snapshot?.contentHtml)
    }

    @Test
    fun invalidJavascriptResultDoesNotCrash() {
        assertEquals(null, WebPageSnapshot.fromJavascriptResult("null"))
        assertEquals(null, WebPageSnapshot.fromJavascriptResult("not-json"))
    }

    @Test
    fun zhihuSnapshotUsesExactInitialStateEntityBeforeDomFallback() {
        val answerId = "2079127079271011205"
        val source = ZhihuSourceResolver.resolve(
            "https://www.zhihu.com/question/26730775/answer/$answerId",
        )
        val snapshot = WebPageSnapshot(
            finalUrl = source.canonicalUrl,
            initialData = """
                {"initialState":{"entities":{"answers":{"$answerId":{
                  "id":"$answerId","question":{"title":"状态标题"},
                  "author":{"name":"状态作者"},"content":"<p>状态正文</p>"
                }}}}}
            """.trimIndent(),
            title = "DOM 标题",
            author = "DOM 作者",
            contentHtml = "<p>DOM 正文</p>",
            visibleText = "DOM 正文",
        )

        val payload = ZhihuWebSnapshotExtractor.extract(snapshot, source)

        assertEquals("<p>状态正文</p>", payload.getString("content"))
    }

    @Test
    fun zhihuSnapshotBuildsPayloadFromRenderedTargetDom() {
        val source = ZhihuSourceResolver.resolve("https://zhuanlan.zhihu.com/p/2078994838666724418")
        val snapshot = WebPageSnapshot(
            finalUrl = source.canonicalUrl,
            title = "文章标题",
            author = "作者",
            contentHtml = "<p>渲染正文</p><img data-original='https://picx.zhimg.com/a.jpg'>",
            visibleText = "文章标题 渲染正文",
        )

        val payload = ZhihuWebSnapshotExtractor.extract(snapshot, source)
        val result = ZhihuMediaParser.normalizeDocument(payload, source)

        assertTrue(result.ok)
        assertEquals("文章标题", result.description)
        assertEquals(1, result.document?.assets?.size)
    }

    @Test
    fun zhihuSnapshotRejectsLoginOrChallengePage() {
        val source = ZhihuSourceResolver.resolve("https://zhuanlan.zhihu.com/p/2078994838666724418")
        val result = runCatching {
            ZhihuWebSnapshotExtractor.extract(
                WebPageSnapshot(
                    finalUrl = "https://www.zhihu.com/signin",
                    visibleText = "登录知乎，验证后继续",
                ),
                source,
            )
        }

        assertFalse(result.isSuccess)
        assertEquals("LOGIN_REQUIRED", (result.exceptionOrNull() as PlatformParseException).code)
    }
}
