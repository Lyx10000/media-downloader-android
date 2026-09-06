package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieEnvironmentTest {
    @Test
    fun refreshesEnvironmentOnceForAuthenticationOrEmptyDetail() {
        assertTrue(shouldRefreshCookieEnvironment("AUTH_OR_RISK", refreshAttempted = false))
        assertTrue(shouldRefreshCookieEnvironment("DETAIL_EMPTY", refreshAttempted = false))
        assertTrue(shouldRefreshCookieEnvironment("LOGIN_REQUIRED", refreshAttempted = false))
        assertTrue(
            shouldRefreshCookieEnvironment(
                "URL_RESOLVE_FAILED",
                refreshAttempted = false,
                platform = SourcePlatform.XIAOHONGSHU,
                supportsTargetPageSnapshot = true,
            ),
        )
        assertFalse(shouldRefreshCookieEnvironment("AUTH_OR_RISK", refreshAttempted = true))
    }

    @Test
    fun doesNotRefreshEnvironmentForOrdinaryNetworkErrors() {
        assertFalse(shouldRefreshCookieEnvironment("NETWORK", refreshAttempted = false))
        assertFalse(shouldRefreshCookieEnvironment("HTTP_ERROR", refreshAttempted = false))
        assertFalse(
            shouldRefreshCookieEnvironment(
                "URL_RESOLVE_FAILED",
                refreshAttempted = false,
                platform = SourcePlatform.DOUYIN,
            ),
        )
    }

    @Test
    fun zhihuDocumentUsesOneTargetPageSnapshotEvenWhenCookieExists() {
        assertTrue(
            shouldRefreshCookieEnvironment(
                "AUTH_OR_RISK",
                refreshAttempted = false,
                platform = SourcePlatform.ZHIHU,
                supportsTargetPageSnapshot = true,
            ),
        )
        assertTrue(
            shouldRefreshCookieEnvironment(
                "AUTH_OR_RISK",
                refreshAttempted = false,
                platform = SourcePlatform.ZHIHU,
                supportsTargetPageSnapshot = true,
            ),
        )
        assertFalse(
            shouldRefreshCookieEnvironment(
                "AUTH_OR_RISK",
                refreshAttempted = false,
                platform = SourcePlatform.ZHIHU,
                supportsTargetPageSnapshot = false,
            ),
        )
    }

    @Test
    fun xiaohongshuAlwaysStartsAnonymouslyEvenWhenCookieExists() {
        assertEquals(
            ParserCredentialMode.ANONYMOUS,
            initialParserCredentialMode(SourcePlatform.XIAOHONGSHU, hasStoredCookie = true),
        )
        assertEquals(
            ParserCredentialMode.ANONYMOUS,
            initialParserCredentialMode(SourcePlatform.XIAOHONGSHU, hasStoredCookie = false),
        )
        assertEquals(
            ParserCredentialMode.STORED_COOKIE,
            initialParserCredentialMode(SourcePlatform.ZHIHU, hasStoredCookie = true),
        )
        assertNull(initialParserCredentialMode(SourcePlatform.DOUYIN, hasStoredCookie = false))
    }

    @Test
    fun xiaohongshuFallsBackBetweenAnonymousAndStoredCookieOnlyOnce() {
        assertEquals(
            ParserCredentialMode.STORED_COOKIE,
            nextParserCredentialMode(
                platform = SourcePlatform.XIAOHONGSHU,
                errorCode = "URL_RESOLVE_FAILED",
                currentMode = ParserCredentialMode.ANONYMOUS,
                hasStoredCookie = true,
                attemptedModes = setOf(ParserCredentialMode.ANONYMOUS),
            ),
        )
        assertEquals(
            ParserCredentialMode.STORED_COOKIE,
            nextParserCredentialMode(
                platform = SourcePlatform.XIAOHONGSHU,
                errorCode = "DETAIL_EMPTY",
                currentMode = ParserCredentialMode.ANONYMOUS,
                hasStoredCookie = true,
                attemptedModes = setOf(ParserCredentialMode.ANONYMOUS),
            ),
        )
        assertEquals(
            ParserCredentialMode.ANONYMOUS,
            nextParserCredentialMode(
                platform = SourcePlatform.XIAOHONGSHU,
                errorCode = "AUTH_OR_RISK",
                currentMode = ParserCredentialMode.STORED_COOKIE,
                hasStoredCookie = true,
                attemptedModes = setOf(ParserCredentialMode.STORED_COOKIE),
            ),
        )
        assertNull(
            nextParserCredentialMode(
                platform = SourcePlatform.XIAOHONGSHU,
                errorCode = "DETAIL_EMPTY",
                currentMode = ParserCredentialMode.STORED_COOKIE,
                hasStoredCookie = true,
                attemptedModes = setOf(
                    ParserCredentialMode.ANONYMOUS,
                    ParserCredentialMode.STORED_COOKIE,
                ),
            ),
        )
        assertNull(
            nextParserCredentialMode(
                platform = SourcePlatform.XIAOHONGSHU,
                errorCode = "NETWORK",
                currentMode = ParserCredentialMode.ANONYMOUS,
                hasStoredCookie = true,
                attemptedModes = setOf(ParserCredentialMode.ANONYMOUS),
            ),
        )
    }
}
