package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.download.AcceleratedDownloader
import com.local.multiplatformdownloader.core.download.AdaptiveDownloadController
import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.download.calculateSampledTransferSpeed
import com.local.multiplatformdownloader.core.download.downloadFileProgress
import com.local.multiplatformdownloader.core.download.formatDownloadStatus
import com.local.multiplatformdownloader.core.download.secureDownloadUrl
import com.local.multiplatformdownloader.core.download.shouldAccelerateDownload
import com.local.multiplatformdownloader.core.network.MediaRequestProfile
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger

@Singleton
internal class MediaTransferClient @Inject constructor(
    private val logger: DiagnosticLogger,
    private val adaptiveDownloadController: AdaptiveDownloadController,
) {
    private val acceleratedDownloader = AcceleratedDownloader()

    suspend fun download(
        taskId: String,
        urls: List<String>,
        target: File,
        label: String,
        referer: String,
        fallbackTotalBytes: Long = -1L,
        requestProfile: MediaRequestProfile = MediaRequestProfile.standard(referer),
        progress: DownloadProgress,
        probeForAcceleration: Boolean = false,
        transferProgress: (suspend (downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) -> Unit)? = null,
    ) {
        suspend fun report(downloaded: Long, total: Long, speed: Long) {
            adaptiveDownloadController.recordTransfer(
                taskId = taskId,
                channel = target.name,
                downloadedBytes = downloaded,
                totalBytes = total,
                bytesPerSecond = speed,
            )
            progress(
                formatDownloadStatus(label, downloaded, total, speed),
                downloadFileProgress(downloaded, total),
                true,
            )
            transferProgress?.invoke(downloaded, total, speed)
        }
        try {
        var lastError: Throwable? = null
        val securedUrls = urls.mapIndexed { addressIndex, originalAddress ->
            secureDownloadUrl(originalAddress).also { address ->
                if (address != originalAddress) {
                    logger.event(taskId, "DOWNLOAD", "CDN_HTTPS_UPGRADED", JSONObject().apply {
                        put("cdn_index", addressIndex)
                        put("cdn_host", URL(address).host)
                    })
                }
            }
        }.distinct()
        val orderedUrls = if (probeForAcceleration || shouldAccelerateDownload(fallbackTotalBytes)) {
            val selection = acceleratedDownloader.selectCandidate(
                addresses = securedUrls,
                referer = referer,
                fallbackTotalBytes = fallbackTotalBytes,
                requestProfile = requestProfile,
            )
            if (selection != null) {
                logger.event(taskId, "DOWNLOAD", "CDN_SELECTED", JSONObject().apply {
                    put("cdn_index", securedUrls.indexOf(selection.address))
                    put("cdn_host", URL(selection.address).host)
                    put("probe_bytes_per_second", selection.bytesPerSecond)
                    put("range_supported", selection.rangeSupported)
                    put("total_bytes", selection.totalBytes)
                })
                if (selection.rangeSupported && shouldAccelerateDownload(selection.totalBytes)) {
                    try {
                        report(0L, selection.totalBytes, 0L)
                        logger.event(taskId, "DOWNLOAD", "RANGE_DOWNLOAD_STARTED", JSONObject().apply {
                            put("parts", ACCELERATED_PART_COUNT)
                            put("cdn_host", URL(selection.address).host)
                            put("total_bytes", selection.totalBytes)
                        })
                        acceleratedDownloader.downloadRanges(
                            selection = selection,
                            target = target,
                            referer = referer,
                            partCount = ACCELERATED_PART_COUNT,
                            requestProfile = requestProfile,
                        ) { downloaded, total, bytesPerSecond ->
                            report(downloaded, total, bytesPerSecond)
                        }
                        logger.event(taskId, "DOWNLOAD", "FILE_DOWNLOADED", JSONObject().apply {
                            put("name", target.name)
                            put("bytes", target.length())
                            put("cdn_index", securedUrls.indexOf(selection.address))
                            put("cdn_host", URL(selection.address).host)
                            put("transfer_mode", "range_$ACCELERATED_PART_COUNT")
                        })
                        return
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        target.delete()
                        logger.event(taskId, "DOWNLOAD", "RANGE_DOWNLOAD_FALLBACK", JSONObject().apply {
                            put("cdn_host", URL(selection.address).host)
                            put("message", error.message ?: error.javaClass.simpleName)
                        })
                    }
                }
                listOf(selection.address) + securedUrls.filterNot { it == selection.address }
            } else {
                securedUrls
            }
        } else {
            securedUrls
        }
        orderedUrls.forEachIndexed { addressIndex, address ->
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    val requestStartedAt = System.nanoTime()
                    val connection = requestProfile.open(address, 20_000, 120_000)
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) error("CDN HTTP $status")
                        val total = connection.contentLengthLong.takeIf { it > 0L }
                            ?: fallbackTotalBytes
                        report(0L, total, 0L)
                        target.outputStream().use { output ->
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(128 * 1024)
                                var downloaded = 0L
                                var lastReportedBytes = 0L
                                var lastReportedAt = System.nanoTime()

                                suspend fun reportDownloadProgress(force: Boolean) {
                                    val now = System.nanoTime()
                                    val elapsed = now - lastReportedAt
                                    if (downloaded == lastReportedBytes) return
                                    if (!force && elapsed < PROGRESS_REPORT_INTERVAL_NANOS) return
                                    val bytesPerSecond = calculateSampledTransferSpeed(
                                        downloadedBytes = downloaded,
                                        lastReportedBytes = lastReportedBytes,
                                        intervalElapsedNanos = elapsed,
                                        requestElapsedNanos = now - requestStartedAt,
                                    )
                                    report(downloaded, total, bytesPerSecond)
                                    lastReportedBytes = downloaded
                                    lastReportedAt = now
                                }

                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    reportDownloadProgress(force = false)
                                }
                                reportDownloadProgress(force = true)
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    logger.event(taskId, "DOWNLOAD", "FILE_DOWNLOADED", JSONObject().apply {
                        put("name", target.name)
                        put("bytes", target.length())
                        put("cdn_index", securedUrls.indexOf(address).takeIf { it >= 0 } ?: addressIndex)
                        put("cdn_host", URL(address).host)
                        put("transfer_mode", "single")
                    })
                    return
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    lastError = error
                    target.delete()
                    logger.event(taskId, "DOWNLOAD", "CDN_ATTEMPT_FAILED", JSONObject().apply {
                        put("cdn_index", securedUrls.indexOf(address).takeIf { it >= 0 } ?: addressIndex)
                        put("attempt", attempt + 1)
                        put("cdn_host", URL(address).host)
                        put("media_label", label)
                        put("message", error.message ?: error.javaClass.simpleName)
                    })
                }
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载地址")
        } finally {
            adaptiveDownloadController.finishTransfer(taskId, target.name)
        }
    }


    private companion object {
        const val ACCELERATED_PART_COUNT = 4
        const val PROGRESS_REPORT_INTERVAL_NANOS = 750_000_000L
    }
}

