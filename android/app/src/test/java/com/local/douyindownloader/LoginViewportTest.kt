package com.local.douyindownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewportTest {
    @Test
    fun onlyDouyinUsesLoginViewportAssistance() {
        assertTrue(shouldAssistLoginViewport(SourcePlatform.DOUYIN))
        assertFalse(shouldAssistLoginViewport(SourcePlatform.XIAOHONGSHU))
        assertFalse(shouldAssistLoginViewport(SourcePlatform.ZHIHU))
    }

    @Test
    fun assistanceScriptKeepsZoomAndCentersLoginArea() {
        assertTrue(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains("user-scalable=yes"))
        assertTrue(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains("scrollIntoView"))
        assertTrue(DOUYIN_LOGIN_VIEWPORT_SCRIPT.contains("setInterval"))
    }
}
