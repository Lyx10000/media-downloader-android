package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.network.jsonEpochMillis
import org.junit.Assert.assertEquals
import org.junit.Test

class PublishedAtTest {
    @Test
    fun `platform timestamps normalize to milliseconds`() {
        assertEquals(1_788_781_816_000L, 1_788_781_816L.jsonEpochMillis())
        assertEquals(1_788_781_816_000L, 1_788_781_816_000L.jsonEpochMillis())
        assertEquals(1_788_781_816_000L, 1_788_781_816_000_000L.jsonEpochMillis())
        assertEquals(0L, 0L.jsonEpochMillis())
    }
}
