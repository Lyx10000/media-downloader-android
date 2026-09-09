package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.calculateSampledTransferSpeed
import com.local.multiplatformdownloader.core.download.downloadFileProgress
import com.local.multiplatformdownloader.core.download.formatDownloadStatus
import com.local.multiplatformdownloader.core.download.formatOutputSummary
import com.local.multiplatformdownloader.core.download.shouldShowDownloadProgress
import com.local.multiplatformdownloader.core.model.TaskOutput


import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {
    @Test
    fun fileProgressRepresentsOnlyTransferredBytes() {
        assertEquals(0, downloadFileProgress(0, 1_000))
        assertEquals(50, downloadFileProgress(500, 1_000))
        assertEquals(100, downloadFileProgress(1_000, 1_000))
        assertEquals(100, downloadFileProgress(2_000, 1_000))
    }

    @Test
    fun unknownTotalDoesNotInventAFilePercentage() {
        assertEquals(0, downloadFileProgress(800, -1))
    }

    @Test
    fun progressBarIsShownOnlyForARealDeterminateTransfer() {
        assertEquals(true, shouldShowDownloadProgress("正在下载视频 · 38% · 2.0 MB/s"))
        assertEquals(false, shouldShowDownloadProgress("准备下载原始视频"))
        assertEquals(false, shouldShowDownloadProgress("分析音视频轨道"))
        assertEquals(false, shouldShowDownloadProgress("正在下载视频 · 已下载 192.0 MB"))
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
    fun firstSampleUsesWholeRequestTimeInsteadOfBufferedReadBurst() {
        val speed = calculateSampledTransferSpeed(
            downloadedBytes = 128L * 1024,
            lastReportedBytes = 0L,
            intervalElapsedNanos = 500_000L,
            requestElapsedNanos = 125_000_000L,
        )

        assertEquals(1_048_576L, speed)
    }

    @Test
    fun laterSamplesKeepUsingTheirPeriodicInterval() {
        val speed = calculateSampledTransferSpeed(
            downloadedBytes = 2L * 1024 * 1024,
            lastReportedBytes = 1L * 1024 * 1024,
            intervalElapsedNanos = 500_000_000L,
            requestElapsedNanos = 10_000_000_000L,
        )

        assertEquals(2L * 1024 * 1024, speed)
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
