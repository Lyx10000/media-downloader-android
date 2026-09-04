package com.local.douyindownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieEnvironmentTest {
    @Test
    fun refreshesEnvironmentOnceForAuthenticationOrEmptyDetail() {
        assertTrue(shouldRefreshCookieEnvironment("AUTH_OR_RISK", refreshAttempted = false))
        assertTrue(shouldRefreshCookieEnvironment("DETAIL_EMPTY", refreshAttempted = false))
        assertTrue(shouldRefreshCookieEnvironment("LOGIN_REQUIRED", refreshAttempted = false))
        assertFalse(shouldRefreshCookieEnvironment("AUTH_OR_RISK", refreshAttempted = true))
    }

    @Test
    fun doesNotRefreshEnvironmentForOrdinaryNetworkErrors() {
        assertFalse(shouldRefreshCookieEnvironment("NETWORK", refreshAttempted = false))
        assertFalse(shouldRefreshCookieEnvironment("HTTP_ERROR", refreshAttempted = false))
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
}
