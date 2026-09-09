package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.network.OkHttpParserClient
import com.local.multiplatformdownloader.platform.douyin.DouyinPlatformParser


import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

@Ignore("Manual network smoke test: short links and risk-control responses change over time")
class LiveParserSmokeTest {
    @Test
    fun `anonymous Kotlin parser follows the same Douyin risk-control path as baseline`() {
        val parser = DouyinPlatformParser(OkHttpParserClient())

        val result = parser.parse("https://v.douyin.com/u-W-ldzTVBs/", "")

        assertFalse(result.ok)
        assertTrue(result.errorCode in setOf("AUTH_OR_RISK", "DETAIL_EMPTY"))
    }
}
