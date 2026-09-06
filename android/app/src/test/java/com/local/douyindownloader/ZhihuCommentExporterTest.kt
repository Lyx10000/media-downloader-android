package com.local.douyindownloader

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhihuCommentExporterTest {
    @Test
    fun exportsRootAndEmbeddedChildCommentsAsMarkdown() {
        val http = object : ParserHttpClient {
            override fun get(
                url: String,
                headers: Map<String, String>,
                cookieHeader: String,
                timeoutSeconds: Long,
            ) = ParserHttpResponse(
                200,
                url,
                emptyList(),
                """
                {
                  "data": [{
                    "id": "root-1",
                    "author": {"member": {"name": "张*三"}},
                    "content": "一级<br>评论",
                    "vote_count": 2,
                    "child_comment_count": 1,
                    "child_comments": [{
                      "id": "child-1",
                      "author": {"member": {"name": "李四"}},
                      "reply_to_author": {"name": "张三"},
                      "content": "回复内容"
                    }]
                  }],
                  "paging": {"is_end": true}
                }
                """.trimIndent(),
                emptyMap(),
            )

            override fun probeContentLength(
                url: String,
                headers: Map<String, String>,
                timeoutSeconds: Long,
            ) = 0L
        }
        val directory = createTempDirectory("zhihu-comments-").toFile()
        val target = File(directory, "comments.md")

        val result = ZhihuCommentExporter(http).export(ZhihuCommentRequest("answer-1", 2), "", target)
        val markdown = target.readText()

        assertTrue(result.count == 2)
        assertTrue(markdown.contains("张\\*三"))
        assertTrue(markdown.contains("一级"))
        assertTrue(markdown.contains("> **李四 回复 张三"))
        directory.deleteRecursively()
    }
}
