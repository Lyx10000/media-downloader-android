package com.local.multiplatformdownloader.feature.home

import com.local.multiplatformdownloader.core.model.SourcePlatform

enum class CookieReadySource(val wireValue: String) {
    PAGE_READY("page_ready"),
    PAGE_ERROR("page_error"),
    TIMEOUT("timeout"),
}

internal enum class ParserCredentialMode(val wireValue: String) {
    ANONYMOUS("anonymous"),
    STORED_COOKIE("stored_cookie"),
}

internal fun initialParserCredentialMode(
    platform: SourcePlatform,
    hasStoredCookie: Boolean,
): ParserCredentialMode? = when {
    platform in setOf(SourcePlatform.XIAOHONGSHU, SourcePlatform.X) ->
        ParserCredentialMode.ANONYMOUS
    hasStoredCookie -> ParserCredentialMode.STORED_COOKIE
    platform.anonymousFirst -> ParserCredentialMode.ANONYMOUS
    else -> null
}

internal fun nextParserCredentialMode(
    platform: SourcePlatform,
    errorCode: String,
    currentMode: ParserCredentialMode,
    hasStoredCookie: Boolean,
    attemptedModes: Set<ParserCredentialMode>,
): ParserCredentialMode? {
    if (platform !in setOf(SourcePlatform.XIAOHONGSHU, SourcePlatform.X, SourcePlatform.INSTAGRAM) ||
        !isRecoverableParseError(platform, errorCode)
    ) {
        return null
    }
    val candidate = when (currentMode) {
        ParserCredentialMode.ANONYMOUS -> ParserCredentialMode.STORED_COOKIE
            .takeIf { hasStoredCookie }
        ParserCredentialMode.STORED_COOKIE -> ParserCredentialMode.ANONYMOUS
    }
    return candidate?.takeUnless(attemptedModes::contains)
}

internal fun shouldRefreshCookieEnvironment(
    errorCode: String,
    refreshAttempted: Boolean,
    platform: SourcePlatform = SourcePlatform.DOUYIN,
    supportsTargetPageSnapshot: Boolean = false,
): Boolean {
    if (refreshAttempted || !isRecoverableParseError(platform, errorCode)) {
        return false
    }
    return platform != SourcePlatform.BILIBILI &&
        (platform != SourcePlatform.ZHIHU || supportsTargetPageSnapshot)
}

private fun isRecoverableParseError(platform: SourcePlatform, errorCode: String): Boolean =
    errorCode in COMMON_RECOVERABLE_PARSE_ERRORS ||
        (platform == SourcePlatform.XIAOHONGSHU && errorCode == "URL_RESOLVE_FAILED")

private val COMMON_RECOVERABLE_PARSE_ERRORS = setOf(
    "AUTH_OR_RISK",
    "DETAIL_EMPTY",
    "LOGIN_REQUIRED",
)


internal class ParseSessionController {
    var id: String = ""
        private set
    var platform: SourcePlatform = SourcePlatform.DOUYIN
        private set
    var sourceUrl: String = ""
        private set
    var supportsPageSnapshot: Boolean = false
        private set
    var storedCookieHeader: String = ""
    val credentialAttempts: MutableSet<ParserCredentialMode> = mutableSetOf()
    var parsingStarted: Boolean = false
        private set
    var environmentRefreshAttempted: Boolean = false
        private set

    fun begin(
        id: String,
        platform: SourcePlatform,
        sourceUrl: String,
        supportsPageSnapshot: Boolean,
        storedCookieHeader: String,
    ) {
        this.id = id
        this.platform = platform
        this.sourceUrl = sourceUrl
        this.supportsPageSnapshot = supportsPageSnapshot
        this.storedCookieHeader = storedCookieHeader
        credentialAttempts.clear()
        parsingStarted = false
        environmentRefreshAttempted = false
    }

    fun tryStartParsing(cookieHeader: String): ParserCredentialMode? {
        if (parsingStarted) return null
        val credentialMode = if (cookieHeader.isBlank()) {
            ParserCredentialMode.ANONYMOUS
        } else {
            ParserCredentialMode.STORED_COOKIE
        }
        credentialAttempts += credentialMode
        parsingStarted = true
        return credentialMode
    }

    fun stopParsing() {
        parsingStarted = false
    }

    fun retainSourceUrl(url: String) {
        sourceUrl = url
    }

    fun markEnvironmentRefreshAttempted() {
        environmentRefreshAttempted = true
        parsingStarted = false
    }

    fun reset() {
        parsingStarted = false
        environmentRefreshAttempted = false
    }
}

