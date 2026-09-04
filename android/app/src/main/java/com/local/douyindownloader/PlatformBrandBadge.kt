package com.local.douyindownloader

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class PlatformBrandPalette(
    val backgroundArgb: Int,
    val contentArgb: Int = 0xFFFFFFFF.toInt(),
)

internal val SourcePlatform.brandPalette: PlatformBrandPalette
    get() = when (this) {
        // Current Douyin app icon background, sampled from the official store artwork.
        SourcePlatform.DOUYIN -> PlatformBrandPalette(backgroundArgb = 0xFF0B0B16.toInt())
        // Xiaohongshu brand red.
        SourcePlatform.XIAOHONGSHU -> PlatformBrandPalette(backgroundArgb = 0xFFFF2442.toInt())
        // Zhihu's canonical brand blue.
        SourcePlatform.ZHIHU -> PlatformBrandPalette(backgroundArgb = 0xFF0066FF.toInt())
    }

@Composable
internal fun PlatformBrandBadge(
    platform: SourcePlatform,
    modifier: Modifier = Modifier,
) {
    val palette = platform.brandPalette
    Surface(
        modifier = modifier,
        color = Color(palette.backgroundArgb),
        contentColor = Color(palette.contentArgb),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = platform.displayName,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
