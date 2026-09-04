package com.local.douyindownloader

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceleratedDownloaderTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach(MockWebServer::shutdown)
    }

    @Test
    fun splitsFileIntoContiguousBalancedRanges() {
        assertEquals(
            listOf(
                DownloadByteRange(0, 24),
                DownloadByteRange(25, 49),
                DownloadByteRange(50, 74),
                DownloadByteRange(75, 99),
            ),
            splitDownloadRanges(totalBytes = 100, requestedParts = 4),
        )
        assertEquals(
            listOf(DownloadByteRange(0, 0), DownloadByteRange(1, 1)),
            splitDownloadRanges(totalBytes = 2, requestedParts = 4),
        )
    }

    @Test
    fun acceleratesOnlyLargeKnownFiles() {
        assertTrue(shouldAccelerateDownload(8L * 1024 * 1024))
        assertEquals(false, shouldAccelerateDownload(8L * 1024 * 1024 - 1L))
        assertEquals(false, shouldAccelerateDownload(-1L))
    }

    @Test
    fun probeSelectsTheFastestRangeCapableCandidate() = runBlocking {
        val payload = ByteArray(1024) { it.toByte() }
        val slow = rangeServer(payload, bodyDelayMs = 250)
        val fast = rangeServer(payload, bodyDelayMs = 5)
        val downloader = AcceleratedDownloader(probeBytes = 512)

        val selection = downloader.selectCandidate(
            addresses = listOf(slow.url("/video.mp4").toString(), fast.url("/video.mp4").toString()),
            referer = "https://www.xiaohongshu.com/",
            fallbackTotalBytes = payload.size.toLong(),
        )

        requireNotNull(selection)
        assertEquals(fast.url("/video.mp4").toString(), selection.address)
        assertEquals(payload.size.toLong(), selection.totalBytes)
        assertTrue(selection.rangeSupported)
    }

    @Test
    fun downloadsFourRangesIntoTheOriginalByteOrder() = runBlocking {
        val payload = ByteArray(10_003) { index -> (index * 31).toByte() }
        val server = rangeServer(payload)
        val target = File.createTempFile("accelerated-download", ".bin").apply { delete() }
        val downloader = AcceleratedDownloader(probeBytes = 128)
        val reports = mutableListOf<Long>()

        try {
            downloader.downloadRanges(
                selection = DownloadCandidateSelection(
                    address = server.url("/video.mp4").toString(),
                    totalBytes = payload.size.toLong(),
                    rangeSupported = true,
                    bytesPerSecond = 1,
                ),
                target = target,
                referer = "https://www.xiaohongshu.com/",
                partCount = 4,
            ) { downloaded, _, _ -> reports += downloaded }

            assertArrayEquals(payload, target.readBytes())
            assertEquals(payload.size.toLong(), reports.last())
            assertEquals(4, server.requestCount)
        } finally {
            target.delete()
        }
    }

    @Test(expected = RangeDownloadUnsupportedException::class)
    fun rejectsServerThatIgnoresRangeRequests() = runBlocking {
        val payload = ByteArray(1024) { it.toByte() }
        val server = MockWebServer().also(servers::add)
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        server.start()
        val target = File.createTempFile("accelerated-download", ".bin").apply { delete() }

        try {
            AcceleratedDownloader().downloadRanges(
                selection = DownloadCandidateSelection(
                    address = server.url("/video.mp4").toString(),
                    totalBytes = payload.size.toLong(),
                    rangeSupported = true,
                    bytesPerSecond = 1,
                ),
                target = target,
                referer = "https://www.xiaohongshu.com/",
                partCount = 1,
            ) { _, _, _ -> }
        } finally {
            target.delete()
        }
    }

    private fun rangeServer(payload: ByteArray, bodyDelayMs: Long = 0): MockWebServer {
        val server = MockWebServer().also(servers::add)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val match = RANGE.matchEntire(request.getHeader("Range").orEmpty())
                    ?: return MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
                val start = match.groupValues[1].toInt()
                val requestedEnd = match.groupValues[2].toInt()
                val end = requestedEnd.coerceAtMost(payload.lastIndex)
                val bytes = payload.copyOfRange(start, end + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setHeader("Content-Length", bytes.size)
                    .setBody(Buffer().write(bytes))
                    .setBodyDelay(bodyDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        return server
    }

    companion object {
        private val RANGE = Regex("bytes=(\\d+)-(\\d+)")
    }
}
