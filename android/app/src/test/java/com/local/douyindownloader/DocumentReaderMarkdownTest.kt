package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DocumentReaderMarkdownTest {
    @Test
    fun rendersDownloadedCommentsWithoutMarkdownControlCharacters() {
        val lines = parseMarkdownDisplayLines(
            """
            # 评论（已保存 2 条）

            ## 张\*三 · 2 赞

            一级评论

            > **李四 回复 张三**
            > 回复内容
            """.trimIndent(),
        )

        assertEquals(MarkdownLineType.HEADING, lines.first().type)
        assertEquals("评论（已保存 2 条）", lines.first().text)
        assertEquals("张*三 · 2 赞", lines[1].text)
        assertEquals(MarkdownLineType.QUOTE, lines[3].type)
        assertEquals("李四 回复 张三", lines[3].text)
        assertFalse(lines.any { it.text.startsWith("#") || it.text.startsWith(">") })
    }
}
