package com.local.multiplatformdownloader.feature.home

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
import com.local.multiplatformdownloader.platform.instagram.INSTAGRAM_LOGIN_VIEWPORT_SCRIPT

/**
 * Platform-specific login presentation rules kept outside the Compose screen.
 *
 * These rules affect only the embedded login environment; parsing and stored credentials remain
 * owned by their platform adapters and Android WebView.
 */
internal fun shouldAssistLoginViewport(platform: SourcePlatform): Boolean =
    platform == SourcePlatform.DOUYIN || platform == SourcePlatform.XIAOHONGSHU ||
        platform == SourcePlatform.X || platform == SourcePlatform.INSTAGRAM

internal fun shouldUseDesktopLoginMode(platform: SourcePlatform): Boolean =
    platform == SourcePlatform.DOUYIN || platform == SourcePlatform.XIAOHONGSHU

internal fun shouldBypassLoginCache(platform: SourcePlatform): Boolean =
    platform == SourcePlatform.X

internal fun loginEnvironmentStartUrl(
    platform: SourcePlatform,
    credentialState: PlatformCredentialState,
): String = if (platform == SourcePlatform.X && credentialState == PlatformCredentialState.DETECTED) {
    "https://x.com/home"
} else if (platform in setOf(SourcePlatform.INSTAGRAM, SourcePlatform.BILIBILI) &&
    credentialState == PlatformCredentialState.DETECTED
) {
    platform.homeUrl
} else {
    platform.loginUrl
}

internal fun shouldFlushLoginCookiesOnPageFinished(platform: SourcePlatform): Boolean =
    platform in setOf(SourcePlatform.X, SourcePlatform.INSTAGRAM, SourcePlatform.BILIBILI)

internal fun loginAssistDelays(platform: SourcePlatform): List<Long> =
    when (platform) {
        SourcePlatform.X -> listOf(0L, 1_500L, 3_500L, 7_000L)
        SourcePlatform.INSTAGRAM ->
            listOf(0L, 1_500L, 3_500L, 7_000L, 12_000L, 20_000L, 30_000L, 45_000L, 60_000L)
        else -> listOf(0L)
    }

internal fun loginViewportScript(platform: SourcePlatform): String = when (platform) {
    SourcePlatform.XIAOHONGSHU -> XHS_LOGIN_VIEWPORT_SCRIPT
    SourcePlatform.X -> X_LOGIN_VIEWPORT_SCRIPT
    SourcePlatform.INSTAGRAM -> INSTAGRAM_LOGIN_VIEWPORT_SCRIPT
    SourcePlatform.DOUYIN -> DOUYIN_LOGIN_VIEWPORT_SCRIPT
    SourcePlatform.ZHIHU, SourcePlatform.BILIBILI -> ""
}
