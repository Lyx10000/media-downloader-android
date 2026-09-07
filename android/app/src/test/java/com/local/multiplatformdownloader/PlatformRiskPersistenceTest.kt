package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.settings.encodePlatformRiskUntil
import com.local.multiplatformdownloader.core.settings.parsePlatformRiskUntil
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
}
