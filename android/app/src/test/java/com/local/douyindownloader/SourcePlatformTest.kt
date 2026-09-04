package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlatformTest {
    @Test
    fun detectsSupportedHostsWithoutAcceptingLookalikes() {
        assertEquals(SourcePlatform.DOUYIN, SourcePlatform.fromUrl("https://v.douyin.com/abc"))
        assertEquals(
            SourcePlatform.XIAOHONGSHU,
            SourcePlatform.fromUrl("https://www.xiaohongshu.com/explore/abc"),
        )
        assertEquals(SourcePlatform.XIAOHONGSHU, SourcePlatform.fromUrl("https://xhslink.cn/abc"))
        assertEquals(SourcePlatform.ZHIHU, SourcePlatform.fromUrl("https://zhuanlan.zhihu.com/p/123"))
        assertEquals(SourcePlatform.ZHIHU, SourcePlatform.fromUrl("https://www.zhihu.com/zvideo/123"))
        assertNull(SourcePlatform.fromUrl("https://xiaohongshu.com.example.org/explore/abc"))
        assertNull(SourcePlatform.fromUrl("https://zhihu.com.example.org/question/1"))
    }

    @Test
    fun extractsZhihuAnswerFromShareText() {
        val source = extractSupportedSource(
            "看看这个回答 https://www.zhihu.com/question/26730775/answer/2079127079271011205，",
        )

        assertEquals(SourcePlatform.ZHIHU, source?.platform)
        assertEquals(
            "https://www.zhihu.com/question/26730775/answer/2079127079271011205",
            source?.url,
        )
    }

    @Test
    fun extractsSupportedLinkFromShareTextAndTrimsPunctuation() {
        val source = extractSupportedSource("复制 https://xhslink.cn/AbCdEf，打开小红书")

        assertEquals(SourcePlatform.XIAOHONGSHU, source?.platform)
        assertEquals("https://xhslink.cn/AbCdEf", source?.url)
    }

    @Test
    fun platformPageCheckIsPlatformSpecific() {
        assertTrue(isPlatformPage("https://www.xiaohongshu.com/", SourcePlatform.XIAOHONGSHU))
        assertFalse(isPlatformPage("https://www.douyin.com/", SourcePlatform.XIAOHONGSHU))
    }
}
