package com.local.douyindownloader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstagramMediaParserTest {
    private val source = InstagramSource.resolve("https://www.instagram.com/reel/Dc6M2YOxQMv/?stkn=example")

    @Test
    fun resolvesShortcodeWithoutLosingIntegerPrecisionAndRejectsOtherHosts() {
        assertEquals("3979549734162727727", source.mediaId)
        assertEquals("Dc6M2YOxQMv", source.shortcode)
        assertThrows(PlatformParseException::class.java) {
            InstagramSource.resolve("https://instagram.com.evil.test/reel/Dc6M2YOxQMv/")
        }
        assertThrows(PlatformParseException::class.java) {
            InstagramSource.resolve("https://www.instagram.com/stories/author/123/")
        }
    }

    @Test
    fun matchesTargetInsidePageStateAndIgnoresRecommendations() {
        val target = video("Dc6M2YOxQMv")
        val root = JSONObject().put("recommendation", video("different"))
            .put("data", JSONObject().put("xig_polaris_media", JSONObject()
                .put("if_not_gated_logged_out", target)))
        val html = "<script type=\"application/json\" data-sjs>${root}</script>"
        assertEquals("Dc6M2YOxQMv", InstagramMediaNormalizer.fromHtml(html, source)?.getString("code"))
        assertNull(InstagramMediaNormalizer.findMedia(video("different"), source))
    }

    @Test
    fun sortsProgressiveQualitiesAndKeepsCaptionAuthorAndAttachmentOrder() {
        val post = video(source.shortcode)
        post.put("carousel_media", org.json.JSONArray().put(video("child1"))
            .put(JSONObject().put("pk", JSONObject.NULL).put("id", "22").put("media_type", 1)
                .put("video_versions", JSONObject.NULL).put("video_url", JSONObject.NULL)
                .put("video_dash_manifest", JSONObject.NULL).put("display_url", "https://cdn.example/photo.jpg"))
            .put(video("child3")))
        val result = InstagramMediaNormalizer.normalize(post, source)
        assertEquals(SourcePlatform.INSTAGRAM, result.platform)
        assertEquals("Example Author", result.author)
        assertEquals("example", result.authorAccountId)
        assertEquals("Caption", result.description)
        assertEquals(listOf(MediaAttachmentKind.VIDEO, MediaAttachmentKind.IMAGE, MediaAttachmentKind.VIDEO),
            result.attachments.map { it.kind })
        assertEquals(listOf(1080, 640), result.attachments.first().variants.map { it.width })
        assertEquals(1, result.imageUrls.size)
        assertEquals("22", result.attachments[1].id)
        assertTrue(result.audioUrls.isEmpty())
    }

    @Test
    fun ageGateProducesSpecificLoginMessageAndDashOnlyIsNotTreatedAsMp4() {
        val gate = JSONObject("""{"code":"Dc6M2YOxQMv","gating_ruling":{"gating_type":3,
            "description":"This content is age-restricted based on your age or account settings."}}""")
        assertEquals("LOGIN_REQUIRED", InstagramMediaNormalizer.gatingError(gate, source)?.code)
        assertTrue(InstagramMediaNormalizer.gatingError(gate, source)!!.message!!.contains("年龄"))
        val dash = JSONObject().put("is_video", true).put("video_dash_manifest", "<MPD/>")
        assertEquals("UNSUPPORTED_STREAM", assertThrows(PlatformParseException::class.java) {
            InstagramMediaNormalizer.normalize(dash, source)
        }.code)
    }

    @Test
    fun authenticatedInfoUsesCookieAndDoesNotProbeWithSessionCredentials() {
        val http = object : ParserHttpClient {
            var requests = 0
            override fun get(url: String, headers: Map<String, String>, cookieHeader: String, timeoutSeconds: Long): ParserHttpResponse {
                requests++
                assertEquals("sessionid=test", cookieHeader)
                val body = if (requests == 1) "<html/>" else {
                    assertTrue(url.endsWith("/media/3979549734162727727/info/"))
                    JSONObject().put("items", org.json.JSONArray().put(video(source.shortcode))).toString()
                }
                return ParserHttpResponse(200, url, emptyList(), body, emptyMap())
            }
            override fun probeContentLength(url: String, headers: Map<String, String>, timeoutSeconds: Long): Long {
                assertFalse(headers.containsKey("Cookie"))
                return 12345
            }
        }
        val result = InstagramPlatformParser(http).parse(source.canonicalUrl, "sessionid=test", null)
        assertTrue(result.message, result.ok)
        assertEquals("media_info", result.parserAttempts.last().strategy)
        assertEquals(12345L, result.variants.first().size)
        assertEquals(2, http.requests)
    }

    @Test
    fun manuallyCheckSuppliedReelWithRealKotlinHttpClient() {
        assumeTrue(System.getenv("INSTAGRAM_LIVE_SMOKE") == "1")
        val url = "https://www.instagram.com/reel/Dc6M2YOxQMv/?stkn=MWtnbzNuemVjM2Y0OA=="
        val result = InstagramPlatformParser(OkHttpParserClient()).parse(url, "", null)
        println("Instagram live: ok=${result.ok}, code=${result.errorCode}, message=${result.message}, attempts=${result.parserAttempts}")
        assertTrue(result.ok || result.errorCode in setOf("LOGIN_REQUIRED", "AUTH_OR_RISK"))
    }

    @Test
    fun manuallyDownloadPublicReelThroughKotlinParser() {
        assumeTrue(System.getenv("INSTAGRAM_LIVE_SMOKE") == "1")
        val realHttp = OkHttpParserClient()
        val tracingHttp = object : ParserHttpClient by realHttp {
            override fun post(url: String, headers: Map<String, String>, cookieHeader: String, body: ByteArray,
                timeoutSeconds: Long): ParserHttpResponse {
                val response = realHttp.post(url, headers, cookieHeader, body, timeoutSeconds)
                val root = runCatching { JSONObject(response.body) }.getOrNull()
                println("GraphQL diagnostic: status=${response.statusCode}, bytes=${response.body.length}, " +
                    "lsdPresent=${!headers["X-FB-LSD"].isNullOrBlank()}, shape=${root?.let { responseShape(it) }}, " +
                    "errors=${root?.optJSONArray("errors")?.optJSONObject(0)?.optString("message")}")
                return response
            }
        }
        val result = InstagramPlatformParser(tracingHttp)
            .parse("https://www.instagram.com/reel/Chunk8-jurw/", "", null)
        println("Instagram public: ok=${result.ok}, code=${result.errorCode}, attempts=${result.parserAttempts}")
        assertTrue(result.message, result.ok)
        val url = result.variants.first().urls.first()
        val request = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-1023").build()
        okhttp3.OkHttpClient().newCall(request).execute().use { response ->
            assertTrue(response.isSuccessful)
            val bytes = response.body!!.source().readByteArray(32)
            assertEquals("ftyp", String(bytes.copyOfRange(4, 8)))
            println("Instagram media: status=${response.code}, format=mp4, variants=${result.variants.size}")
        }
    }

    private fun video(code: String) = JSONObject("""{
        "code":"$code", "is_video":true,
        "user":{"pk":"123","username":"example","full_name":"Example Author"},
        "caption":{"text":"Caption"},
        "video_versions":[{"url":"https://cdn.example/low.mp4","width":640,"height":360},
            {"url":"https://cdn.example/high.mp4","width":1080,"height":1920}],
        "image_versions2":{"candidates":[{"url":"https://cdn.example/cover.jpg","width":1080,"height":1920}]}
    }""")
}
