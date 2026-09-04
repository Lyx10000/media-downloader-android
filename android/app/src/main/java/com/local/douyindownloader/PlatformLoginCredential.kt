package com.local.douyindownloader

import java.util.Locale

enum class PlatformCredentialState {
    DETECTED,
    NOT_DETECTED,
}

internal fun detectPlatformCredential(
    platform: SourcePlatform,
    cookieHeader: String,
): PlatformCredentialState {
    val cookieNames = cookieHeader.split(';')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0 || part.substring(separator + 1).trim().isBlank()) return@mapNotNull null
            part.substring(0, separator)
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf(String::isNotBlank)
        }
        .toSet()
    val loginCookies = when (platform) {
        SourcePlatform.DOUYIN -> setOf("sessionid", "sessionid_ss", "sid_tt")
        SourcePlatform.XIAOHONGSHU -> setOf("web_session")
        SourcePlatform.ZHIHU -> setOf("z_c0")
    }
    return if (cookieNames.any(loginCookies::contains)) {
        PlatformCredentialState.DETECTED
    } else {
        PlatformCredentialState.NOT_DETECTED
    }
}
