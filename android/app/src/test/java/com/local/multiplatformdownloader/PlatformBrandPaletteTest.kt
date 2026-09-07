package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.platform.common.bilibiliBadgeGradientArgb
import com.local.multiplatformdownloader.platform.common.brandPalette


import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformBrandPaletteTest {
    @Test
    fun `uses platform brand backgrounds and readable labels`() {
        assertEquals(0xFF0B0B16.toInt(), SourcePlatform.DOUYIN.brandPalette.backgroundArgb)
        assertEquals(0xFFFF2442.toInt(), SourcePlatform.XIAOHONGSHU.brandPalette.backgroundArgb)
        assertEquals(0xFF0066FF.toInt(), SourcePlatform.ZHIHU.brandPalette.backgroundArgb)

        assertEquals(listOf(0xFFFF709F.toInt(), 0xFFFF4E86.toInt(), 0xFFFF3071.toInt()), bilibiliBadgeGradientArgb)
        assertEquals(0xFF161616.toInt(), SourcePlatform.BILIBILI.brandPalette.contentArgb)
        SourcePlatform.entries.filterNot { it == SourcePlatform.BILIBILI }.forEach { platform ->
            assertEquals(0xFFFFFFFF.toInt(), platform.brandPalette.contentArgb)
        }
    }
}
