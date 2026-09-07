package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.feature.home.videoModes


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModesTest {
    @Test
    fun mp4OnlyIsAvailableForMuxedAndSeparateTrackVideos() {
        listOf(true, false).forEach { muxed ->
            val modes = videoModes(muxed)
            assertEquals(1, modes.count { it.first == DownloadMode.MP4_ONLY })
            assertTrue(modes.first { it.first == DownloadMode.MP4_ONLY }.second.contains("MP4"))
        }
    }
}
