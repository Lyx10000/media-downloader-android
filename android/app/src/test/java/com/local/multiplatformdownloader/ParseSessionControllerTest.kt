package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.feature.home.ParseSessionController
import com.local.multiplatformdownloader.feature.home.ParserCredentialMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseSessionControllerTest {
    @Test
    fun `begin replaces prior session state`() {
        val controller = ParseSessionController()

        controller.begin(
            id = "first",
            platform = SourcePlatform.DOUYIN,
            sourceUrl = "https://example.com/first",
            supportsPageSnapshot = false,
            storedCookieHeader = "cookie=first",
        )
        controller.tryStartParsing("")
        controller.markEnvironmentRefreshAttempted()

        controller.begin(
            id = "second",
            platform = SourcePlatform.ZHIHU,
            sourceUrl = "https://example.com/second",
            supportsPageSnapshot = true,
            storedCookieHeader = "cookie=second",
        )

        assertEquals("second", controller.id)
        assertEquals(SourcePlatform.ZHIHU, controller.platform)
        assertEquals("https://example.com/second", controller.sourceUrl)
        assertEquals("cookie=second", controller.storedCookieHeader)
        assertTrue(controller.supportsPageSnapshot)
        assertTrue(controller.credentialAttempts.isEmpty())
        assertFalse(controller.parsingStarted)
        assertFalse(controller.environmentRefreshAttempted)
    }

    @Test
    fun `only one parse attempt can start until stopped`() {
        val controller = ParseSessionController()
        controller.begin("id", SourcePlatform.X, "https://x.com/i/status/1", true, "")

        assertEquals(ParserCredentialMode.ANONYMOUS, controller.tryStartParsing(""))
        assertNull(controller.tryStartParsing("cookie=value"))

        controller.stopParsing()

        assertEquals(ParserCredentialMode.STORED_COOKIE, controller.tryStartParsing("cookie=value"))
        assertEquals(
            setOf(ParserCredentialMode.ANONYMOUS, ParserCredentialMode.STORED_COOKIE),
            controller.credentialAttempts,
        )
    }
}
