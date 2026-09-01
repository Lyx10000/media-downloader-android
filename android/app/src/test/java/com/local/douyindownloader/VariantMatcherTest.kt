package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantMatcherTest {
    @Test
    fun matchesResolutionCodecAndFpsExactly() {
        val previous = variant(1080, 1920, "H.264", 60)
        val current = listOf(
            variant(2160, 3840, "H.265", 60),
            variant(1080, 1920, "H.264", 60),
        )

        val match = matchVariant(previous, current)

        assertEquals(1, match.index)
        assertTrue(match.exact)
    }

    @Test
    fun choosesClosestVariantWhenExactOneDisappears() {
        val previous = variant(1080, 1920, "H.264", 60)
        val current = listOf(
            variant(2160, 3840, "H.265", 60),
            variant(1080, 1920, "H.265", 60),
            variant(720, 1280, "H.264", 30),
        )

        val match = matchVariant(previous, current)

        assertEquals(1, match.index)
        assertFalse(match.exact)
    }

    @Test
    fun usesHighestWhenPreviousQualityIsUnknown() {
        val match = matchVariant(null, listOf(variant(2160, 3840, "H.265", 60)))

        assertEquals(0, match.index)
        assertFalse(match.exact)
    }

    @Test
    fun retrySourceUsesStableWorkIdInsteadOfExpiredShortLink() {
        val result = ParseResult(ok = true, awemeId = "7670820885931028910", kind = "image")
        val spec = TaskSpec(
            taskId = "task-id",
            createdAt = 1_700_000_000_000,
            result = result,
            variantIndex = 0,
            mode = "merge_keep",
            sourceText = "https://v.douyin.com/expired/",
        )

        assertEquals(
            "https://www.douyin.com/note/7670820885931028910",
            spec.stableSource(),
        )
    }

    private fun variant(width: Int, height: Int, codec: String, fps: Int) = MediaVariant(
        width = width,
        height = height,
        bitrate = 4_000_000,
        fps = fps,
        codec = codec,
        size = 10_000_000,
        sizeSource = "api",
        urls = listOf("https://cdn.example/$width.mp4"),
    )
}
