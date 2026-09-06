package com.local.douyindownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhihuQuestionParserTest {
    @Test
    fun normalizesQuestionMetadata() {
        val source = ZhihuSourceResolver.resolve("https://www.zhihu.com/question/1234567890123456789")
        val result = ZhihuQuestionParser.normalizeQuestion(
            JSONObject().put("title", "一个问题").put("answer_count", 42),
            source,
        )

        assertTrue(result.ok)
        assertEquals("1234567890123456789", result.question?.questionId)
        assertEquals(42, result.question?.answerCount)
        assertEquals(DocumentType.QUESTION, result.document?.type)
    }

    @Test
    fun normalizesAnswerPageAndUsesNextOffset() {
        val payload = JSONObject(
            """
            {
              "data": [
                {
                  "id": "99",
                  "author": {"name": "答主"},
                  "excerpt": "<b>回答摘要</b>",
                  "voteup_count": 8,
                  "comment_count": 3
                }
              ],
              "paging": {
                "is_end": false,
                "next": "https://www.zhihu.com/api/v4/questions/1/answers?offset=21&limit=20"
              }
            }
            """.trimIndent(),
        )

        val page = ZhihuQuestionParser.normalizePage(payload, "1", 20)

        assertTrue(page.hasMore)
        assertEquals(21, page.nextOffset)
        assertEquals("答主", page.answers.single().author)
        assertEquals("回答摘要", page.answers.single().excerpt)
        assertEquals(21, page.answers.single().position)
    }

    @Test
    fun preservesQuestionAndCommentMetadataInTaskSpec() {
        val source = ZhihuSourceResolver.resolve("https://www.zhihu.com/question/123")
        val result = ZhihuQuestionParser.normalizeQuestion(
            JSONObject().put("title", "测试问题").put("answer_count", 1),
            source,
        )
        val spec = TaskSpec(
            taskId = "task-1",
            createdAt = 1L,
            result = result,
            variantIndex = 0,
            mode = DownloadMode.MERGE_KEEP,
            sourceText = source.canonicalUrl,
            questionArchiveId = "123",
            questionChild = true,
            zhihuCommentRequest = ZhihuCommentRequest("456", 7),
        )

        val restored = TaskSpec.fromJson(spec.toJson())

        assertEquals("123", restored.questionArchiveId)
        assertTrue(restored.questionChild)
        assertEquals("456", restored.zhihuCommentRequest?.answerId)
        assertEquals(7, restored.zhihuCommentRequest?.expectedCount)
        assertEquals(DocumentType.QUESTION, restored.result.document?.type)
        assertEquals("123", restored.result.question?.questionId)
    }

    @Test
    fun buildsStableQuestionIndexLinks() {
        val archive = ZhihuQuestionArchive(
            questionId = "123",
            parentTaskId = "task",
            title = "测试问题",
            canonicalUrl = "https://www.zhihu.com/question/123",
            answerCount = 1,
            nextOffset = 0,
            hasMore = false,
            scope = ZhihuQuestionDownloadScope.ALL,
            includeComments = true,
            status = ZhihuQuestionStatus.COMPLETE,
            taskFolder = "知乎/测试问题_123",
            createdAt = 1L,
            refreshedAt = 1L,
        )
        val answer = ZhihuQuestionAnswer(
            questionId = "123",
            answerId = "456",
            position = 1,
            author = "答主",
            excerpt = "内容",
            voteupCount = 0,
            commentCount = 0,
            canonicalUrl = "https://www.zhihu.com/question/123/answer/456",
            taskId = "child",
            status = ZhihuQuestionAnswerStatus.COMPLETE,
        )

        val markdown = buildQuestionIndexMarkdown(archive, listOf(answer))

        assertTrue(markdown.contains("# 测试问题"))
        assertTrue(markdown.contains("answers/456_答主/answer.md"))
    }

    @Test
    fun allowsAnotherBatchForLegacyFirstPageArchive() {
        val archive = questionArchive(
            answerCount = 994,
            nextOffset = 31,
            hasMore = false,
        )

        assertTrue(canContinueQuestionArchive(archive, downloadedAnswers = 20))
    }

    @Test
    fun stopsOfferingContinuationAfterRemoteEnd() {
        val archive = questionArchive(
            answerCount = 994,
            nextOffset = -1,
            hasMore = false,
        )

        assertTrue(!canContinueQuestionArchive(archive, downloadedAnswers = 20))
    }

    private fun questionArchive(
        answerCount: Int,
        nextOffset: Int,
        hasMore: Boolean,
    ) = ZhihuQuestionArchive(
        questionId = "123",
        parentTaskId = "task",
        title = "测试问题",
        canonicalUrl = "https://www.zhihu.com/question/123",
        answerCount = answerCount,
        nextOffset = nextOffset,
        hasMore = hasMore,
        scope = ZhihuQuestionDownloadScope.FIRST_PAGE,
        includeComments = true,
        status = ZhihuQuestionStatus.COMPLETE,
        taskFolder = "知乎/测试问题_123",
        createdAt = 1L,
        refreshedAt = 1L,
    )
}
