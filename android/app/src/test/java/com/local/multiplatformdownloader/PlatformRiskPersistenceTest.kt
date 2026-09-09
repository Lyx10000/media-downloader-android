package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.settings.encodePlatformRiskUntil
import com.local.multiplatformdownloader.core.settings.encodePlatformRiskCooldownMinutes
import com.local.multiplatformdownloader.core.settings.parsePlatformRiskUntil
import com.local.multiplatformdownloader.core.settings.parsePlatformRiskCooldownMinutes
import com.local.multiplatformdownloader.core.settings.DEFAULT_PLATFORM_RISK_COOLDOWN_MINUTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformRiskPersistenceTest {
    @Test
    fun `risk deadlines round trip by platform wire name`() {
        val expected = mapOf(SourcePlatform.DOUYIN to 123L, SourcePlatform.BILIBILI to 456L)

        assertEquals(expected, parsePlatformRiskUntil(encodePlatformRiskUntil(expected)))
    }

    @Test
    fun `invalid or nonpositive persisted values fail safely`() {
        assertTrue(parsePlatformRiskUntil("not-json").isEmpty())
        assertTrue(parsePlatformRiskUntil("{\"douyin\":0}").isEmpty())
    }

    @Test
    fun `platform cooldown minutes round trip independently`() {
        val expected = SourcePlatform.entries.associateWith { platform ->
            if (platform == SourcePlatform.DOUYIN) 12 else 5
        }

        assertEquals(expected, parsePlatformRiskCooldownMinutes(encodePlatformRiskCooldownMinutes(expected)))
    }

    @Test
    fun `platform cooldown minutes use defaults and clamp persisted values`() {
        val parsed = parsePlatformRiskCooldownMinutes("{\"douyin\":0,\"x\":99,\"zhihu\":8}")

        assertEquals(DEFAULT_PLATFORM_RISK_COOLDOWN_MINUTES, parsed.getValue(SourcePlatform.DOUYIN))
        assertEquals(30, parsed.getValue(SourcePlatform.X))
        assertEquals(8, parsed.getValue(SourcePlatform.ZHIHU))
        assertEquals(DEFAULT_PLATFORM_RISK_COOLDOWN_MINUTES, parsed.getValue(SourcePlatform.BILIBILI))
    }
}
