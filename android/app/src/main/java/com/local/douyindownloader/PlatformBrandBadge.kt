package com.local.douyindownloader

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class PlatformBrandPalette(
    val backgroundArgb: Int,
    val contentArgb: Int = 0xFFFFFFFF.toInt(),
)

// Sampled from bilibili's current official App Store icon (2026-09-06):
// https://apps.apple.com/cn/app/id736536022 — top / middle / bottom, sRGB PNG.
internal val bilibiliBadgeGradientArgb = listOf(0xFFFF709F.toInt(), 0xFFFF4E86.toInt(), 0xFFFF3071.toInt())

internal val SourcePlatform.brandPalette: PlatformBrandPalette
    get() = when (this) {
        // Current Douyin app icon background, sampled from the official store artwork.
        SourcePlatform.DOUYIN -> PlatformBrandPalette(backgroundArgb = 0xFF0B0B16.toInt())
        // Xiaohongshu brand red.
        SourcePlatform.XIAOHONGSHU -> PlatformBrandPalette(backgroundArgb = 0xFFFF2442.toInt())
        // Zhihu's canonical brand blue.
        SourcePlatform.ZHIHU -> PlatformBrandPalette(backgroundArgb = 0xFF0066FF.toInt())
        // X uses a black app-icon field with a white mark.
        SourcePlatform.X -> PlatformBrandPalette(backgroundArgb = 0xFF000000.toInt())
        SourcePlatform.INSTAGRAM -> PlatformBrandPalette(backgroundArgb = 0xFFFF0169.toInt())
        SourcePlatform.BILIBILI -> PlatformBrandPalette(
            backgroundArgb = bilibiliBadgeGradientArgb[1], contentArgb = 0xFF161616.toInt(),
        )
    }

@Composable
internal fun PlatformBrandBadge(
    platform: SourcePlatform,
    modifier: Modifier = Modifier,
) {
    val palette = platform.brandPalette
    val background = if (platform == SourcePlatform.INSTAGRAM) {
        Modifier.drawWithCache {
            // Gradient stops from https://about.instagram.com/brand/gradient (September 2026).
            val gradient = Brush.linearGradient(
                0f to Color(0xFFFF7A00),
                0.5652f to Color(0xFFFF0169),
                1f to Color(0xFFD300C5),
                start = Offset(0f, size.height),
                end = Offset(size.width, 0f),
            )
            onDrawBehind {
                drawRect(gradient)
                // Keep small white labels readable even over the bright orange end.
                drawRect(Color.Black.copy(alpha = 0.28f))
            }
        }
    } else if (platform == SourcePlatform.BILIBILI) {
        Modifier.drawWithCache {
            val gradient = Brush.verticalGradient(bilibiliBadgeGradientArgb.map { Color(it) })
            onDrawBehind { drawRect(gradient) }
        }
    } else {
        Modifier
    }
    Surface(
        modifier = modifier,
        color = Color(palette.backgroundArgb),
        contentColor = Color(palette.contentArgb),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = platform.displayName,
            modifier = background.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
