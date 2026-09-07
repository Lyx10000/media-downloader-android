package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.feature.home.DOUYIN_LOGIN_VIEWPORT_SCRIPT
import com.local.multiplatformdownloader.feature.home.XHS_LOGIN_VIEWPORT_SCRIPT
import com.local.multiplatformdownloader.feature.home.X_LOGIN_VIEWPORT_SCRIPT
import com.local.multiplatformdownloader.feature.home.loginAssistDelays
import com.local.multiplatformdownloader.feature.home.loginEnvironmentStartUrl
import com.local.multiplatformdownloader.feature.home.loginViewportScript
import com.local.multiplatformdownloader.feature.home.shouldAssistLoginViewport
import com.local.multiplatformdownloader.feature.home.shouldBypassLoginCache
import com.local.multiplatformdownloader.feature.home.shouldFlushLoginCookiesOnPageFinished
import com.local.multiplatformdownloader.feature.home.shouldUseDesktopLoginMode
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState


import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewportTest {
    @Test
    fun douyinXiaohongshuAndXUseLoginViewportAssistance() {
        assertTrue(shouldAssistLoginViewport(SourcePlatform.DOUYIN))
        assertTrue(shouldAssistLoginViewport(SourcePlatform.XIAOHONGSHU))
        assertFalse(shouldAssistLoginViewport(SourcePlatform.ZHIHU))
        assertTrue(shouldAssistLoginViewport(SourcePlatform.X))
    }

    @Test
    fun onlyDouyinAndXiaohongshuUseDesktopLoginMode() {
        assertTrue(shouldUseDesktopLoginMode(SourcePlatform.DOUYIN))
        assertTrue(shouldUseDesktopLoginMode(SourcePlatform.XIAOHONGSHU))
        assertFalse(shouldUseDesktopLoginMode(SourcePlatform.ZHIHU))
        assertFalse(shouldUseDesktopLoginMode(SourcePlatform.X))
    }

    @Test
    fun xLoginBypassesCachedShellAndGetsDelayedAssistPasses() {
        assertTrue(shouldBypassLoginCache(SourcePlatform.X))
        assertFalse(shouldBypassLoginCache(SourcePlatform.DOUYIN))
        assertEquals(listOf(0L, 1_500L, 3_500L, 7_000L), loginAssistDelays(SourcePlatform.X))
        assertEquals(listOf(0L), loginAssistDelays(SourcePlatform.ZHIHU))
    }

    @Test
    fun xReusesDetectedSessionFromHomeAndFlushesCookiesDuringLoginFlow() {
        assertEquals(
            "https://x.com/home",
            loginEnvironmentStartUrl(SourcePlatform.X, PlatformCredentialState.DETECTED),
        )
        assertEquals(
            SourcePlatform.X.loginUrl,
            loginEnvironmentStartUrl(SourcePlatform.X, PlatformCredentialState.NOT_DETECTED),
        )
        assertEquals(
            SourcePlatform.DOUYIN.loginUrl,
            loginEnvironmentStartUrl(SourcePlatform.DOUYIN, PlatformCredentialState.DETECTED),
        )
        assertTrue(shouldFlushLoginCookiesOnPageFinished(SourcePlatform.X))
        assertFalse(shouldFlushLoginCookiesOnPageFinished(SourcePlatform.DOUYIN))
    }

    @Test
    fun assistanceScriptCentersLoginWithoutLockingThePageViewport() {
        assertTrue(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains("scrollIntoView"))
        assertTrue(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains("setInterval"))
        assertTrue(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains(".click()"))
        assertFalse(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains("meta[name=\"viewport\"]"))
        assertFalse(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains("width=device-width"))
    }

    @Test
    fun xiaohongshuAssistanceCanOpenAndCenterLoginPanel() {
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains("登录探索更多内容"))
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains("input[type=\"tel\"]"))
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains("scrollIntoView"))
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains(".click()"))
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains("website-login"))
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains("overflow-y"))
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains("pan-y pinch-zoom"))
        assertTrue(XHS_LOGIN_VIEWPORT_SCRIPT.contains("-webkit-overflow-scrolling"))
        assertTrue(loginViewportScript(SourcePlatform.XIAOHONGSHU) === XHS_LOGIN_VIEWPORT_SCRIPT)
    }

    @Test
    fun xDiagnosticRepairsScrollerAndWatchesDynamicLoginSteps() {
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("input[type=\"text\"]"))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("input[type=\"password\"]"))
        assertFalse(X_LOGIN_VIEWPORT_SCRIPT.contains("input:not([type=\"hidden\"]"))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("visualViewport"))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("ancestors="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("position="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("transform="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("overflow="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("margin="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("padding="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("scroll="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("x-dom-diagnostic-v1"))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains(".jf-vscroller"))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("diagnosticViewportHeight"))
        assertTrue(
            X_LOGIN_VIEWPORT_SCRIPT.contains(
                "'height', diagnosticViewportHeight + 'px'",
            ),
        )
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("vscroller="))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("x-vscroller-fix-v1"))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("__aggregateXScrollerWatcher"))
        assertTrue(X_LOGIN_VIEWPORT_SCRIPT.contains("x-scroller-watch-v1"))
        assertTrue(loginViewportScript(SourcePlatform.X) === X_LOGIN_VIEWPORT_SCRIPT)
    }
}
