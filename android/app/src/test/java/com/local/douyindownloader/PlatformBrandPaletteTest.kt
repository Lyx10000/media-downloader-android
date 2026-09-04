package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformBrandPaletteTest {
    @Test
    fun `uses current platform brand backgrounds with white labels`() {
        assertEquals(0xFF0B0B16.toInt(), SourcePlatform.DOUYIN.brandPalette.backgroundArgb)
        assertEquals(0xFFFF2442.toInt(), SourcePlatform.XIAOHONGSHU.brandPalette.backgroundArgb)
        assertEquals(0xFF0066FF.toInt(), SourcePlatform.ZHIHU.brandPalette.backgroundArgb)

        SourcePlatform.entries.forEach { platform ->
            assertEquals(0xFFFFFFFF.toInt(), platform.brandPalette.contentArgb)
        }
    }
}
