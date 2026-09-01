package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntentsTest {
    @Test
    fun keepsExactMimeTypeForSingleFile() {
        assertEquals("video/mp4", commonShareMimeType(listOf("video/mp4")))
        assertEquals(
            "video/mp4",
            commonShareMimeType(listOf("video/mp4", "video/mp4")),
        )
    }

    @Test
    fun groupsFilesFromSameMediaFamily() {
        assertEquals(
            "image/*",
            commonShareMimeType(listOf("image/jpeg", "image/png")),
        )
    }

    @Test
    fun usesWildcardForMixedOrUnknownFiles() {
        assertEquals("*/*", commonShareMimeType(listOf("video/mp4", "audio/mp4")))
        assertEquals("*/*", commonShareMimeType(listOf(null)))
    }
}
