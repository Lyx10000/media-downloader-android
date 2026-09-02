package com.local.douyindownloader

import java.util.Locale
import kotlin.math.roundToInt

internal fun downloadTaskProgress(
    downloadedBytes: Long,
    totalBytes: Long,
    startProgress: Int,
    endProgress: Int,
): Int {
    val safeStart = startProgress.coerceIn(0, 100)
    val safeEnd = endProgress.coerceIn(safeStart, 100)
    if (totalBytes <= 0L) return safeStart
    val ratio = (downloadedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
    return (safeStart + ratio * (safeEnd - safeStart)).toInt().coerceIn(safeStart, safeEnd)
}

internal fun formatDownloadStatus(
    label: String,
    downloadedBytes: Long,
    totalBytes: Long,
    bytesPerSecond: Long,
): String {
    val safeDownloaded = downloadedBytes.coerceAtLeast(0L)
    val speed = "${formatByteSize(bytesPerSecond.coerceAtLeast(0L))}/s"
    return if (totalBytes > 0L) {
        val percent = (safeDownloaded.toDouble() * 100 / totalBytes)
            .coerceIn(0.0, 100.0)
            .roundToInt()
        "正在下载$label · $percent% · $speed · " +
            "${formatByteSize(safeDownloaded)} / ${formatByteSize(totalBytes)}"
    } else {
        "正在下载$label · 已下载 ${formatByteSize(safeDownloaded)} · $speed"
    }
}

internal fun formatOutputSummary(outputs: List<TaskOutput>): String? {
    if (outputs.isEmpty() || outputs.any { it.sizeBytes <= 0L }) return null
    return "共 ${outputs.size} 个文件 · 合计 ${formatByteSize(outputs.sumOf(TaskOutput::sizeBytes))}"
}

internal fun formatByteSize(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = safeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$safeBytes B"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
