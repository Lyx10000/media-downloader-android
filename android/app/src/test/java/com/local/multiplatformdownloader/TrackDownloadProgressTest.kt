package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.DownloadTrackProgress
import com.local.multiplatformdownloader.core.download.TaskTrackDownloadProgress
import com.local.multiplatformdownloader.core.download.combinedTrackProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackDownloadProgressTest {
    @Test
    fun `combined progress gives both tracks equal completion weight`() {
        val progress = TaskTrackDownloadProgress(
            video = DownloadTrackProgress(80L, 100L, 10L),
            audio = DownloadTrackProgress(20L, 100L, 5L),
        )

        assertEquals(50, combinedTrackProgress(progress))
    }

    @Test
    fun `unknown track total remains incomplete`() {
        val progress = TaskTrackDownloadProgress(
            video = DownloadTrackProgress(100L, 100L, 10L),
            audio = DownloadTrackProgress(10L, 0L, 5L),
        )

        assertEquals(50, combinedTrackProgress(progress))
    }
}
