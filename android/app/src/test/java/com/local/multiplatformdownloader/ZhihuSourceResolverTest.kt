package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.platform.zhihu.ZhihuContentType
import com.local.multiplatformdownloader.platform.zhihu.ZhihuSourceResolver


import org.junit.Assert.assertEquals
import org.junit.Test

class ZhihuSourceResolverTest {
    @Test
    fun resolvesSupportedContentTypesAndCanonicalUrls() {
        val cases = listOf(
            Triple(
                "https://zhuanlan.zhihu.com/p/2078994838666724418?share_code=a",
                ZhihuContentType.ARTICLE,
                "https://zhuanlan.zhihu.com/p/2078994838666724418",
            ),
            Triple(
                "https://www.zhihu.com/question/26730775/answer/2079127079271011205?utm_psn=1",
                ZhihuContentType.ANSWER,
                "https://www.zhihu.com/question/26730775/answer/2079127079271011205",
            ),
            Triple(
                "https://www.zhihu.com/pin/1234567890",
                ZhihuContentType.PIN,
                "https://www.zhihu.com/pin/1234567890",
            ),
            Triple(
                "https://www.zhihu.com/zvideo/2035289178502645430?share_code=a",
                ZhihuContentType.VIDEO,
                "https://www.zhihu.com/zvideo/2035289178502645430",
            ),
            Triple(
                "https://www.zhihu.com/tardis/zm/art/2078994838666724418",
                ZhihuContentType.ARTICLE,
                "https://zhuanlan.zhihu.com/p/2078994838666724418",
            ),
        )

        cases.forEach { (url, type, canonical) ->
            val result = ZhihuSourceResolver.resolve(url)
            assertEquals(type, result.type)
            assertEquals(canonical, result.canonicalUrl)
        }
    }

    @Test
    fun resolvesQuestionWithoutSpecificAnswer() {
        val source = ZhihuSourceResolver.resolve(
            "https://www.zhihu.com/question/2078437532639949793?utm_psn=1",
        )

        assertEquals(ZhihuContentType.QUESTION, source.type)
        assertEquals("2078437532639949793", source.questionId)
        assertEquals("2078437532639949793", source.contentId)
        assertEquals(
            "https://www.zhihu.com/question/2078437532639949793",
            source.canonicalUrl,
        )
    }

    @Test
    fun keepsIdsAsStrings() {
        val source = ZhihuSourceResolver.resolve(
            "https://www.zhihu.com/question/2078437532639949793/answer/2078466148119721025",
        )

        assertEquals("2078437532639949793", source.questionId)
        assertEquals("2078466148119721025", source.contentId)
    }
}
