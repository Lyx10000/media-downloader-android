package com.local.douyindownloader

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BilibiliHttpBoundaryTest {
    @Test fun `single hop does not forward explicit credentials to a redirect target`() {
        MockWebServer().use { origin ->
            MockWebServer().use { destination ->
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/target")))
                val response = OkHttpParserClient().getWithoutRedirects(origin.url("/start").toString(),
                    emptyMap(), "SESSDATA=synthetic", 2)
                assertEquals(302, response.statusCode)
                assertEquals("SESSDATA=synthetic", origin.takeRequest().getHeader("Cookie"))
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test fun `a redirect loop stops after one anonymous request`() {
        val requests = mutableListOf<String>()
        val http = object : ParserHttpClient {
            override fun getWithoutRedirects(url: String, headers: Map<String, String>, cookieHeader: String, timeoutSeconds: Long): ParserHttpResponse {
                assertTrue(cookieHeader.isEmpty())
                requests += url
                return ParserHttpResponse(302, url, emptyList(), "", mapOf("location" to url))
            }
            override fun get(url: String, headers: Map<String, String>, cookieHeader: String, timeoutSeconds: Long): ParserHttpResponse = error("Not used")
            override fun probeContentLength(url: String, headers: Map<String, String>, timeoutSeconds: Long): Long = error("Not used")
        }
        val result = BilibiliPlatformParser(http).parse("https://b23.tv/loop", "SESSDATA=synthetic", null)
        assertEquals("URL_RESOLVE_FAILED", result.errorCode)
        assertEquals(1, requests.size)
    }
}
