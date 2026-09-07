package com.local.multiplatformdownloader.platform.common

import com.local.multiplatformdownloader.core.model.SourcePlatform

import java.util.Locale
import org.json.JSONObject

enum class PlatformCredentialState {
    DETECTED,
    ANONYMOUS,
    CHALLENGE_REQUIRED,
    EXPIRED,
    CHECKING,
    UNVERIFIED,
    NOT_DETECTED,
}

internal class XiaohongshuCredentialValidationCache {
    private var cookieHeader = ""
    private var state: PlatformCredentialState? = null
    private var validatedAt = 0L

    fun reusableState(cookieHeader: String, now: Long): PlatformCredentialState? {
        if (cookieHeader.isBlank() || cookieHeader != this.cookieHeader) return null
        val cachedState = state ?: return null
        val maxAge = when (cachedState) {
            PlatformCredentialState.DETECTED -> 30 * 60_000L
            PlatformCredentialState.ANONYMOUS,
            PlatformCredentialState.CHALLENGE_REQUIRED,
            PlatformCredentialState.EXPIRED,
            -> 5 * 60_000L
            PlatformCredentialState.UNVERIFIED -> 60_000L
            PlatformCredentialState.CHECKING,
            PlatformCredentialState.NOT_DETECTED,
            -> 0L
        }
        return cachedState.takeIf { maxAge > 0L && now - validatedAt in 0..maxAge }
    }

    fun update(cookieHeader: String, state: PlatformCredentialState, now: Long) {
        this.cookieHeader = cookieHeader
        this.state = state
        validatedAt = now
    }

    fun clear() {
        cookieHeader = ""
        state = null
        validatedAt = 0L
    }
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
        SourcePlatform.X -> setOf("auth_token", "ct0")
        SourcePlatform.INSTAGRAM -> setOf("sessionid")
        SourcePlatform.BILIBILI -> setOf("sessdata")
    }
    val detected = if (platform == SourcePlatform.X) {
        cookieNames.containsAll(loginCookies)
    } else {
        cookieNames.any(loginCookies::contains)
    }
    return if (detected) {
        PlatformCredentialState.DETECTED
    } else {
        PlatformCredentialState.NOT_DETECTED
    }
}

internal fun classifyXiaohongshuCredentialSnapshot(payload: String): PlatformCredentialState {
    val root = runCatching { JSONObject(payload) }.getOrNull()
        ?: return PlatformCredentialState.UNVERIFIED
    return when {
        root.optBoolean("loggedIn") -> PlatformCredentialState.DETECTED
        root.optBoolean("challengeRequired") -> PlatformCredentialState.CHALLENGE_REQUIRED
        root.optBoolean("loginPrompt") -> PlatformCredentialState.ANONYMOUS
        else -> PlatformCredentialState.UNVERIFIED
    }
}
