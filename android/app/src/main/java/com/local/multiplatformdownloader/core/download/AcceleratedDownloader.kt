package com.local.multiplatformdownloader.core.download

import com.local.multiplatformdownloader.core.network.MediaRequestProfile

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class DownloadByteRange(
    val startInclusive: Long,
    val endInclusive: Long,
) {
    val size: Long get() = endInclusive - startInclusive + 1L
}

internal data class DownloadCandidateSelection(
    val address: String,
    val totalBytes: Long,
    val rangeSupported: Boolean,
    val bytesPerSecond: Long,
)

internal class RangeDownloadUnsupportedException(message: String) : IOException(message)

internal fun splitDownloadRanges(totalBytes: Long, requestedParts: Int): List<DownloadByteRange> {
    require(totalBytes > 0L) { "文件大小必须大于 0" }
    require(requestedParts > 0) { "分段数量必须大于 0" }
    val partCount = min(totalBytes, requestedParts.toLong()).toInt()
    val baseSize = totalBytes / partCount
    val remainder = totalBytes % partCount
    var start = 0L
    return List(partCount) { index ->
        val size = baseSize + if (index < remainder) 1L else 0L
        DownloadByteRange(start, start + size - 1L).also { start += size }
    }
}

internal fun shouldAccelerateDownload(totalBytes: Long): Boolean =
    totalBytes >= AcceleratedDownloader.MIN_ACCELERATED_BYTES

internal class AcceleratedDownloader(
    private val probeBytes: Int = DEFAULT_PROBE_BYTES,
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 8_000,
) {
    private val connectionPermits = Semaphore(MAX_CONCURRENT_MEDIA_CONNECTIONS)

    suspend fun selectCandidate(
        addresses: List<String>,
        referer: String,
        fallbackTotalBytes: Long,
        requestProfile: MediaRequestProfile = MediaRequestProfile.standard(referer),
    ): DownloadCandidateSelection? = coroutineScope {
        addresses.distinct().take(MAX_PROBE_CANDIDATES).map { address ->
            async(Dispatchers.IO) {
                try {
                    connectionPermits.withPermit {
                        probeCandidate(address, requestProfile, fallbackTotalBytes)
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    null
                }
            }
        }.awaitAll().filterNotNull().maxWithOrNull(
            compareBy<DownloadCandidateSelection> { it.bytesPerSecond }
                .thenBy { it.rangeSupported },
        )
    }

    suspend fun downloadRanges(
        selection: DownloadCandidateSelection,
        target: File,
        referer: String,
        partCount: Int = DEFAULT_PART_COUNT,
        requestProfile: MediaRequestProfile = MediaRequestProfile.standard(referer),
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!selection.rangeSupported || selection.totalBytes <= 0L) {
            throw RangeDownloadUnsupportedException("CDN 不支持可靠的分段下载")
        }
        target.parentFile?.mkdirs()
        val ranges = splitDownloadRanges(selection.totalBytes, partCount)
        val progress = AggregateDownloadProgress(selection.totalBytes, onProgress)
        try {
            RandomAccessFile(target, "rw").use { file -> file.setLength(selection.totalBytes) }
            coroutineScope {
                ranges.map { range ->
                    async(Dispatchers.IO) {
                        connectionPermits.withPermit {
                            downloadRangeWithRetries(
                                address = selection.address,
                                requestProfile = requestProfile,
                                target = target,
                                range = range,
                                expectedTotalBytes = selection.totalBytes,
                                progress = progress,
                            )
                        }
                    }
                }.awaitAll()
            }
            progress.complete()
            if (target.length() != selection.totalBytes) {
                throw IOException("分段下载后的文件大小不正确")
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun probeCandidate(
        address: String,
        requestProfile: MediaRequestProfile,
        fallbackTotalBytes: Long,
    ): DownloadCandidateSelection {
        val startedAt = System.nanoTime()
        val connection = requestProfile.open(address, connectTimeoutMs, readTimeoutMs,
            mapOf("Range" to "bytes=0-${probeBytes - 1}", "Accept-Encoding" to "identity"))
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("CDN HTTP $status")
            val contentRange = parseContentRange(connection.getHeaderField("Content-Range"))
            val rangeSupported = status == HttpURLConnection.HTTP_PARTIAL &&
                contentRange?.startInclusive == 0L && contentRange.totalBytes > 0L
            val totalBytes = contentRange?.totalBytes?.takeIf { it > 0L }
                ?: if (status == HttpURLConnection.HTTP_OK) {
                    connection.contentLengthLong.takeIf { it > 0L }
                } else {
                    null
                }
                ?: fallbackTotalBytes
            var sampledBytes = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(PROBE_BUFFER_BYTES)
                while (sampledBytes < probeBytes) {
                    val requested = min(buffer.size.toLong(), probeBytes - sampledBytes).toInt()
                    val count = input.read(buffer, 0, requested)
                    if (count < 0) break
                    sampledBytes += count
                }
            }
            if (sampledBytes <= 0L) throw IOException("CDN 测速没有返回数据")
            val elapsedNanos = (System.nanoTime() - startedAt).coerceAtLeast(1L)
            val bytesPerSecond = (sampledBytes.toDouble() * NANOS_PER_SECOND / elapsedNanos)
                .toLong().coerceAtLeast(1L)
            return DownloadCandidateSelection(
                address = address,
                totalBytes = totalBytes,
                rangeSupported = rangeSupported,
                bytesPerSecond = bytesPerSecond,
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadRangeWithRetries(
        address: String,
        requestProfile: MediaRequestProfile,
        target: File,
        range: DownloadByteRange,
        expectedTotalBytes: Long,
        progress: AggregateDownloadProgress,
    ) {
        var lastError: Throwable? = null
        var creditedBytes = 0L
        repeat(RANGE_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                downloadRange(address, requestProfile, target, range, expectedTotalBytes) { writtenBytes ->
                    if (writtenBytes > creditedBytes) {
                        progress.add(writtenBytes - creditedBytes)
                        creditedBytes = writtenBytes
                    }
                }
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (error is RangeDownloadUnsupportedException) throw error
                lastError = error
            }
        }
        throw lastError ?: IOException("分段下载失败")
    }

    private suspend fun downloadRange(
        address: String,
        requestProfile: MediaRequestProfile,
        target: File,
        range: DownloadByteRange,
        expectedTotalBytes: Long,
        onBytesWritten: suspend (Long) -> Unit,
    ) {
        val connection = requestProfile.open(address, connectTimeoutMs, readTimeoutMs,
            mapOf("Range" to "bytes=${range.startInclusive}-${range.endInclusive}", "Accept-Encoding" to "identity"))
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                throw RangeDownloadUnsupportedException("CDN 忽略了 Range 请求：HTTP $status")
            }
            val responseRange = parseContentRange(connection.getHeaderField("Content-Range"))
                ?: throw RangeDownloadUnsupportedException("CDN 没有返回 Content-Range")
            if (responseRange.startInclusive != range.startInclusive ||
                responseRange.endInclusive != range.endInclusive ||
                responseRange.totalBytes != expectedTotalBytes
            ) {
                throw RangeDownloadUnsupportedException("CDN 返回的分段范围不一致")
            }

            RandomAccessFile(target, "rw").use { output ->
                output.seek(range.startInclusive)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var remaining = range.size
                    var writtenBytes = 0L
                    while (remaining > 0L) {
                        currentCoroutineContext().ensureActive()
                        val requested = min(buffer.size.toLong(), remaining).toInt()
                        val count = input.read(buffer, 0, requested)
                        if (count < 0) throw IOException("CDN 提前结束分段响应")
                        output.write(buffer, 0, count)
                        remaining -= count
                        writtenBytes += count
                        onBytesWritten(writtenBytes)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class ResponseContentRange(
        val startInclusive: Long,
        val endInclusive: Long,
        val totalBytes: Long,
    )

    private fun parseContentRange(value: String?): ResponseContentRange? {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty().trim()) ?: return null
        return ResponseContentRange(
            startInclusive = match.groupValues[1].toLongOrNull() ?: return null,
            endInclusive = match.groupValues[2].toLongOrNull() ?: return null,
            totalBytes = match.groupValues[3].toLongOrNull() ?: return null,
        )
    }

    private class AggregateDownloadProgress(
        private val totalBytes: Long,
        private val onProgress: suspend (Long, Long, Long) -> Unit,
    ) {
        private val downloadedBytes = AtomicLong(0L)
        private val reportMutex = Mutex()
        private var lastReportedBytes = 0L
        private var lastReportedAt = System.nanoTime()

        suspend fun add(count: Long) {
            val downloaded = downloadedBytes.addAndGet(count)
            val now = System.nanoTime()
            if (downloaded < totalBytes && now - lastReportedAt < REPORT_INTERVAL_NANOS) return
            reportMutex.withLock { report(force = downloaded >= totalBytes) }
        }

        suspend fun complete() {
            reportMutex.withLock { report(force = true) }
        }

        private suspend fun report(force: Boolean) {
            val now = System.nanoTime()
            val downloaded = downloadedBytes.get()
            val elapsed = now - lastReportedAt
            if (downloaded == lastReportedBytes) return
            if (!force && elapsed < REPORT_INTERVAL_NANOS) return
            val speed = if (elapsed > 0L) {
                ((downloaded - lastReportedBytes).toDouble() * NANOS_PER_SECOND / elapsed)
                    .toLong().coerceAtLeast(0L)
            } else {
                0L
            }
            onProgress(downloaded, totalBytes, speed)
            lastReportedBytes = downloaded
            lastReportedAt = now
        }
    }

    companion object {
        const val MIN_ACCELERATED_BYTES = 8L * 1024 * 1024
        private const val DEFAULT_PROBE_BYTES = 256 * 1024
        private const val DEFAULT_PART_COUNT = 4
        private const val MAX_CONCURRENT_MEDIA_CONNECTIONS = 8
        private const val MAX_PROBE_CANDIDATES = 4
        private const val PROBE_BUFFER_BYTES = 64 * 1024
        private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
        private const val RANGE_ATTEMPTS = 3
        private const val REPORT_INTERVAL_NANOS = 750_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
