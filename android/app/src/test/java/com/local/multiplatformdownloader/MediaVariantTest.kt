package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.MediaVariant


import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVariantTest {
    @Test
    fun distinguishesExactEstimatedAndUnknownSizes() {
        val exact = variant(size = 19_500_000, source = "cdn").label
        val estimated = variant(size = 19_500_000, source = "estimated").label
        val unknown = variant(size = 0, source = "unknown").label

        assertTrue(exact.contains("MB"))
        assertFalse(exact.contains("约"))
        assertTrue(estimated.contains("约"))
        assertTrue(unknown, unknown.contains("大小未知"))
    }

    private fun variant(size: Long, source: String) = MediaVariant(
        width = 1080,
        height = 1920,
        bitrate = 4_000_000,
        fps = 30,
        codec = "H.264",
        size = size,
        sizeSource = source,
        urls = listOf("https://cdn.example/video.mp4"),
    )
}
