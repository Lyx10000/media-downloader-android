package com.local.douyindownloader

import org.json.JSONObject
import java.net.URI
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, anonymous, two user-provided samples only. Never emits response bodies or signed URLs. */
class BilibiliLiveSmokeTest {
    @Test fun userProvidedSamples() {
        assumeTrue(System.getenv("BILIBILI_LIVE_SMOKE") == "1")
        val client = OkHttpParserClient()
        val observingClient = object : ParserHttpClient by client {
            override fun getWithoutRedirects(url: String, headers: Map<String, String>, cookieHeader: String, timeoutSeconds: Long): ParserHttpResponse {
                check(cookieHeader.isEmpty())
                val response = client.getWithoutRedirects(url, headers, "", timeoutSeconds)
                if (url.contains("/playurl?")) {
                    val data = runCatching { JSONObject(response.body).optJSONObject("data") }.getOrNull()
                    val dash = data?.optJSONObject("dash")
                    val tracks = dash?.optJSONArray("video")
                    val summary = if (tracks == null) emptyList() else (0 until tracks.length()).map { i ->
                        val track = tracks.getJSONObject(i)
                        "${track.optInt("id")}:${track.optInt("width")}x${track.optInt("height")}:${track.optString("codecs") }"
                    }
                    println("BILIBILI_STRUCTURE http=${response.statusCode} dash=${dash != null} durl=${data?.optJSONArray("durl")?.length() ?: 0} video=$summary audio=${dash?.optJSONArray("audio")?.length() ?: 0}")
                }
                return response
            }
        }
        val parser = BilibiliPlatformParser(observingClient)
        for (id in listOf("BV1vobn6dEAR", "BV1Z6t96QEG4")) {
            val result = parser.parse("https://www.bilibili.com/video/$id/", "", null)
            println("BILIBILI_SAMPLE $id ok=${result.ok} code=${result.errorCode} variants=${result.variants.map { "${it.width}x${it.height}" }} audioCandidates=${result.audioUrls.size}")
            // A failed/risk response terminates this loop: no fallbacks or further samples.
            assertTrue("$id ${result.errorCode}", result.ok)
            assertTrue(result.contentId.startsWith("$id:"))
            assertTrue(result.canonicalUrl.endsWith("?p=1"))
            assertTrue(result.audioUrls.isNotEmpty())
            probe("video", result.variants.last().urls)
            probe("audio", result.audioUrls)
        }
    }

    private fun probe(track: String, candidates: List<String>) {
        val address = candidates.firstOrNull { URI(it).host.endsWith(".bilivideo.com") } ?: candidates.first()
        val response = BilibiliRequestProfile.media.open(address, 15_000, 15_000,
            mapOf("Range" to "bytes=0-65535"))
        try {
            assertTrue("CDN rejected media: ${response.responseCode}; stop probes", response.responseCode in setOf(200, 206))
            val bytes = response.inputStream.use { it.readNBytes(65536) }
            assertTrue("Empty media response", bytes.isNotEmpty())
            val header = bytes.toString(Charsets.ISO_8859_1)
            assertTrue("Not an MP4 track", header.contains("ftyp"))
            println("BILIBILI_PROBE $track http=${response.responseCode} bytes=${bytes.size} ftyp=${header.contains("ftyp")} moov=${header.contains("moov")}")
        } finally { response.disconnect() }
    }
}
