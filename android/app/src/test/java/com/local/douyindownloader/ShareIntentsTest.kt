package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareIntentsTest {
    @Test
    fun recognisesSupportedVideoAndAudioFiles() {
        assertEquals("video/mp4", mediaMimeType("video_1.mp4"))
        assertEquals("audio/mpeg", mediaMimeType("music.mp3"))
        assertEquals("audio/mp4", mediaMimeType("track.m4a"))
        assertEquals("audio/aac", mediaMimeType("track.aac"))
    }

    @Test
    fun recognisesCommonImageFiles() {
        val expected = mapOf(
            "photo.jpg" to "image/jpeg",
            "photo.jpeg" to "image/jpeg",
            "photo.png" to "image/png",
            "photo.webp" to "image/webp",
            "photo.gif" to "image/gif",
            "photo.bmp" to "image/bmp",
            "photo.heic" to "image/heic",
            "photo.heif" to "image/heif",
            "photo.avif" to "image/avif",
        )

        expected.forEach { (name, mime) ->
            assertEquals(name, mime, mediaMimeType(name))
        }
    }

    @Test
    fun fileExtensionCorrectsGenericProviderMimeType() {
        assertEquals(
            "audio/mpeg",
            mediaMimeType("music.mp3", "application/octet-stream"),
        )
        assertEquals(
            "video/mp4",
            mediaMimeType("video.mp4", "image/*"),
        )
    }

    @Test
    fun multiSelectionMustStayInOneMediaCategory() {
        assertEquals(
            true,
            canShareTogether(listOf("image/jpeg", "image/png", "image/webp")),
        )
        assertEquals(
            true,
            canShareTogether(listOf("audio/mpeg", "audio/mp4")),
        )
        assertEquals(
            false,
            canShareTogether(listOf("video/mp4", "audio/mp4")),
        )
    }

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

    @Test
    fun createsSafeUniqueCacheNamesWithoutChangingExtension() {
        assertEquals("01_video_1.mp4", shareCacheFileName(0, "video_1.mp4"))
        assertEquals("02_bad_name.m4a", shareCacheFileName(1, "../bad/name.m4a"))
    }
}
