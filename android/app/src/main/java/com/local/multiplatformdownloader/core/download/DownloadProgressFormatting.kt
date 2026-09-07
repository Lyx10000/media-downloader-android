package com.local.multiplatformdownloader.core.download

import com.local.multiplatformdownloader.core.model.TaskOutput

import java.util.Locale
import kotlin.math.roundToInt

internal fun downloadFileProgress(
    downloadedBytes: Long,
    totalBytes: Long,
): Int {
    if (totalBytes <= 0L) return 0
    return (downloadedBytes.toDouble() * 100 / totalBytes)
        .coerceIn(0.0, 100.0)
        .roundToInt()
}

internal fun shouldShowDownloadProgress(stage: String): Boolean =
    stage.startsWith("正在下载") && DETERMINATE_PERCENT.containsMatchIn(stage)

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

private val DETERMINATE_PERCENT = Regex(" · \\d{1,3}% · ")
