package com.local.multiplatformdownloader


import com.local.multiplatformdownloader.core.download.AcceleratedDownloader
import com.local.multiplatformdownloader.core.network.BilibiliRequestProfile
import com.local.multiplatformdownloader.core.network.MediaRequestProfile


import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaRequestProfileTest {
    @Test fun bilibiliAllowsOnlyKnownHttpsCdnTargets() {
        BilibiliRequestProfile.media.validate(URL("https://upos.bilivideo.com/a?sign=unchanged"))
        for (url in listOf("http://upos.bilivideo.com/a", "https://bilivideo.com.evil.test/a",
            "https://evilbilivideo.com/a", "https://user@upos.bilivideo.com/a", "https://upos.bilivideo.com:444/a")) {
            try { BilibiliRequestProfile.media.validate(URL(url)); fail(url) } catch (_: IOException) { }
        }
    }

    @Test fun credentialsCannotBeAccidentallyAdded() {
        for (key in listOf("Cookie", "cookie", "Authorization")) {
            try { MediaRequestProfile(mapOf(key to "secret")); fail(key) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun standardHeadersRemainUnchanged() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val connection = MediaRequestProfile.standard("https://x.com/").open(server.url("/").toString(), 2000, 2000)
            try { assertEquals(200, connection.responseCode) } finally { connection.disconnect() }
            val request = server.takeRequest()
            assertEquals("https://x.com/", request.getHeader("Referer"))
            assertTrue(request.getHeader("User-Agent")!!.contains("Android 15"))
            assertNull(request.getHeader("Origin"))
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun sharedHeadersReachProbeAndRangeDownload() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-3/4").setBody("data")) }
            val profile = MediaRequestProfile(BilibiliRequestProfile.apiHeaders + ("Accept" to "*/*"))
            val downloader = AcceleratedDownloader(probeBytes = 4)
            val selected = downloader.selectCandidate(listOf(server.url("/").toString()), "ignored", 4, profile)!!
            val target = File.createTempFile("media-profile", ".mp4")
            try {
                downloader.downloadRanges(selected, target, "ignored", 1, profile) { _, _, _ -> }
                assertEquals("data", target.readText())
                repeat(2) {
                    val request = server.takeRequest()
                    assertEquals("https://m.bilibili.com", request.getHeader("Referer"))
                    assertEquals("https://m.bilibili.com", request.getHeader("Origin"))
                    assertTrue(request.getHeader("User-Agent")!!.contains("Edg/124"))
                    assertEquals("bytes=0-3", request.getHeader("Range"))
                    assertNull(request.getHeader("Cookie"))
                }
            } finally { target.delete() }
        }
    }

    @Test fun preflightUsesRangeGetAndReadsTotalSize() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/98765").setBody("a"))
            assertEquals(98765L, MediaRequestProfile.standard("ref").probeContentLength(server.url("/").toString()))
            assertEquals("bytes=0-0", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun redirectIsValidatedBeforeOpeningNextConnection() {
        var opened = 0
        var disconnected = false
        val profile = MediaRequestProfile(emptyMap(), setOf("bilivideo.com")) { url ->
            opened++
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() { disconnected = true }
                override fun usingProxy() = false
                override fun getResponseCode() = 302
                override fun getHeaderField(name: String?) = if (name == "Location") "https://evil.test/media" else null
            }
        }
        try { profile.open("https://upos.bilivideo.com/media", 2000, 2000); fail() } catch (_: IOException) { }
        assertEquals(1, opened)
        assertTrue(disconnected)
    }
}
