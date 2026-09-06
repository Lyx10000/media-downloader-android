package com.local.douyindownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewportTest {
    @Test
    fun douyinAndXiaohongshuUseLoginViewportAssistance() {
        assertTrue(shouldAssistLoginViewport(SourcePlatform.DOUYIN))
        assertTrue(shouldAssistLoginViewport(SourcePlatform.XIAOHONGSHU))
        assertFalse(shouldAssistLoginViewport(SourcePlatform.ZHIHU))
    }

    @Test
    fun douyinAndXiaohongshuUseDesktopLoginMode() {
        assertTrue(shouldUseDesktopLoginMode(SourcePlatform.DOUYIN))
        assertTrue(shouldUseDesktopLoginMode(SourcePlatform.XIAOHONGSHU))
        assertFalse(shouldUseDesktopLoginMode(SourcePlatform.ZHIHU))
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
}
