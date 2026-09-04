package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRedownloadStrategyTest {
    @Test
    fun xiaohongshuRetriesAnonymouslyBeforeUsingStoredCookie() {
        assertEquals(
            listOf(
                RedownloadCredential.ANONYMOUS,
                RedownloadCredential.ANONYMOUS,
                RedownloadCredential.STORED_COOKIE,
            ),
            redownloadCredentialPlan(SourcePlatform.XIAOHONGSHU, hasStoredCookie = true),
        )
        assertEquals(
            listOf(RedownloadCredential.ANONYMOUS, RedownloadCredential.ANONYMOUS),
            redownloadCredentialPlan(SourcePlatform.XIAOHONGSHU, hasStoredCookie = false),
        )
    }

    @Test
    fun douyinRegeneratesItsSignedRequestUpToThreeTimes() {
        assertEquals(
            listOf(
                RedownloadCredential.STORED_COOKIE,
                RedownloadCredential.STORED_COOKIE,
                RedownloadCredential.STORED_COOKIE,
            ),
            redownloadCredentialPlan(SourcePlatform.DOUYIN, hasStoredCookie = true),
        )
    }

    @Test
    fun onlyTransientParseFailuresAdvanceToAnotherAttempt() {
        assertTrue(isRetriableRedownloadParseFailure("AUTH_OR_RISK"))
        assertTrue(isRetriableRedownloadParseFailure("DETAIL_EMPTY"))
        assertTrue(isRetriableRedownloadParseFailure("NETWORK"))
        assertFalse(isRetriableRedownloadParseFailure("CONTENT_UNAVAILABLE"))
        assertFalse(isRetriableRedownloadParseFailure("UNSUPPORTED_URL"))
    }

    @Test
    fun storedImageAndVideoSourcesCanBeUsedWhenReparseIsBlocked() {
        val image = ParseResult(
            ok = true,
            platform = SourcePlatform.XIAOHONGSHU,
            kind = MediaKind.IMAGE,
            imageCandidates = listOf(listOf("https://cdn.example/image.jpg")),
        )
        val video = ParseResult(
            ok = true,
            platform = SourcePlatform.DOUYIN,
            kind = MediaKind.VIDEO,
            variants = listOf(
                MediaVariant(1080, 1920, 0, 30, "h264", 0, "unknown", listOf("https://cdn.example/video.mp4")),
            ),
        )
        assertTrue(hasReusableDownloadSources(image))
        assertTrue(hasReusableDownloadSources(video))
        assertFalse(hasReusableDownloadSources(ParseResult(ok = true, kind = MediaKind.IMAGE)))
        assertFalse(hasReusableDownloadSources(ParseResult(ok = true, kind = MediaKind.VIDEO)))
    }

    @Test
    fun storedDocumentCanAlwaysRegenerateItsMarkdown() {
        val document = ParseResult(
            ok = true,
            platform = SourcePlatform.ZHIHU,
            kind = MediaKind.DOCUMENT,
            document = DocumentContent(
                type = DocumentType.ANSWER,
                title = "纯文本回答",
                author = "作者",
                sourceUrl = "https://www.zhihu.com/answer/1",
                blocks = listOf(DocumentBlock(DocumentBlockType.PARAGRAPH, text = "正文")),
                assets = emptyList(),
            ),
        )
        assertTrue(hasReusableDownloadSources(document))
    }
}
