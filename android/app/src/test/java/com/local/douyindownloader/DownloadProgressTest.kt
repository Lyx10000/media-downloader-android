package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {
    @Test
    fun fileProgressStaysInsideItsTaskStageRange() {
        assertEquals(15, downloadTaskProgress(0, 1_000, 15, 70))
        assertEquals(42, downloadTaskProgress(500, 1_000, 15, 70))
        assertEquals(70, downloadTaskProgress(1_000, 1_000, 15, 70))
        assertEquals(70, downloadTaskProgress(2_000, 1_000, 15, 70))
    }

    @Test
    fun unknownTotalKeepsTaskProgressStable() {
        assertEquals(15, downloadTaskProgress(800, -1, 15, 70))
    }

    @Test
    fun downloadStatusShowsPercentSpeedAndTransferredBytes() {
        val text = formatDownloadStatus(
            label = "视频",
            downloadedBytes = 192L * 1024 * 1024,
            totalBytes = 512L * 1024 * 1024,
            bytesPerSecond = 2L * 1024 * 1024,
        )

        assertTrue(text, text.contains("38%"))
        assertTrue(text, text.contains("2.0 MB/s"))
        assertTrue(text, text.contains("192.0 MB / 512.0 MB"))
    }

    @Test
    fun unknownTotalStillShowsActivityAndSpeed() {
        val text = formatDownloadStatus("视频", 192L * 1024 * 1024, -1, 2L * 1024 * 1024)

        assertTrue(text, text.contains("已下载 192.0 MB"))
        assertTrue(text, text.contains("2.0 MB/s"))
    }

    @Test
    fun completedTaskSummaryUsesActualSavedFileSizes() {
        val outputs = listOf(
            TaskOutput("content://video", sizeBytes = 900L * 1024 * 1024),
            TaskOutput("content://audio", sizeBytes = 100L * 1024 * 1024),
        )

        assertEquals("共 2 个文件 · 合计 1000.0 MB", formatOutputSummary(outputs))
        assertNull(formatOutputSummary(listOf(TaskOutput("content://legacy"))))
    }

    @Test
    fun taskOutputSizeDefaultsToZeroForLegacyCallers() {
        assertEquals(1234L, TaskOutput("content://video", sizeBytes = 1234).sizeBytes)
        assertEquals(0L, TaskOutput("content://legacy").sizeBytes)
    }
}
