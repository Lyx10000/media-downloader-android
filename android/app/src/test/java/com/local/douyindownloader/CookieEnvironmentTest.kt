package com.local.douyindownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieEnvironmentTest {
    @Test
    fun refreshesEnvironmentOnceForAuthenticationOrEmptyDetail() {
        assertTrue(shouldRefreshCookieEnvironment("AUTH_OR_RISK", refreshAttempted = false))
        assertTrue(shouldRefreshCookieEnvironment("DETAIL_EMPTY", refreshAttempted = false))
        assertFalse(shouldRefreshCookieEnvironment("AUTH_OR_RISK", refreshAttempted = true))
    }

    @Test
    fun doesNotRefreshEnvironmentForOrdinaryNetworkErrors() {
        assertFalse(shouldRefreshCookieEnvironment("NETWORK", refreshAttempted = false))
        assertFalse(shouldRefreshCookieEnvironment("HTTP_ERROR", refreshAttempted = false))
    }

    @Test
    fun recognisesOnlyDouyinHosts() {
        assertTrue(isDouyinPage("https://www.douyin.com/"))
        assertTrue(isDouyinPage("https://v.douyin.com/example"))
        assertFalse(isDouyinPage("https://douyin.com.example.org/"))
    }
}
