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
        assertNull(SourcePlatform.fromUrl("https://xiaohongshu.com.example.org/explore/abc"))
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
