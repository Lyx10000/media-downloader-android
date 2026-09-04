package com.local.douyindownloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinMediaNormalizerTest {
    @Test
    fun `normalizes the Python video fixture without losing quality`() {
        val detail = JSONObject(
            """
            {
              "aweme_id":"1234567890123456789",
              "desc":"测试作品",
              "author":{"nickname":"测试作者"},
              "video":{
                "cover":{"url_list":["https://cdn.example/cover.jpeg"]},
                "bit_rate":[
                  {"bit_rate":4000000,"is_h265":true,"FPS":60,"play_addr":{"width":2160,"height":3840,"data_size":10000000,"url_list":["https://cdn.example/4k.mp4"]}},
                  {"bit_rate":2000000,"FPS":30,"play_addr":{"width":1080,"height":1920,"data_size":5000000,"url_list":["https://cdn.example/1080.mp4"]}}
                ]
              }
            }
            """.trimIndent(),
        )

        val result = DouyinMediaNormalizer.normalize(detail, "ignored", MediaKind.VIDEO)

        assertTrue(result.ok)
        assertEquals(SourcePlatform.DOUYIN, result.platform)
        assertEquals("1234567890123456789", result.contentId)
        assertEquals("测试作者", result.author)
        assertEquals(2, result.variants.size)
        assertEquals("H.265", result.variants[0].codec)
        assertEquals(3840, result.variants[0].height)
        assertTrue(result.audioUrls.isEmpty())
    }

    @Test
    fun `download originals precede display images and watermarks are last`() {
        val image = JSONObject(
            """
            {
              "download_url_list":[
                "https://download.example/source-q100.webp",
                "https://p3.douyinpic.com/id~tplv-dy-water-v2:q100.webp"
              ],
              "origin_url":{"url_list":["https://cdn.example/original.jpeg"]},
              "url_list":["https://cdn.example/display-q75.jpeg","https://cdn.example/display-q90.jpeg"]
            }
            """.trimIndent(),
        )

        val urls = DouyinMediaNormalizer.extractImageUrls(image)

        assertEquals("https://download.example/source-q100.webp", urls[0])
        assertTrue(urls.indexOf("https://cdn.example/original.jpeg") < urls.indexOf("https://cdn.example/display-q90.jpeg"))
        assertTrue(urls.last().contains("tplv-dy-water"))
    }

    @Test
    fun `video variants match Python grouping sorting sizes and audio choice`() {
        val video = JSONObject(
            """
            {
              "duration":10000,
              "bit_rate":[
                {"bit_rate":8000000,"FPS":30,"play_addr":{"width":1080,"height":1920,"url_list":["https://cdn.example/1080-high.mp4"]}},
                {"bit_rate":5000000,"is_h265":true,"FPS":60,"play_addr":{"width":2160,"height":3840,"file_size":80000000,"url_list":["https://cdn.example/4k.mp4"]}},
                {"bit_rate":6000000,"FPS":30,"play_addr":{"width":1080,"height":1920,"url_list":["https://cdn.example/1080-low.mp4"]}}
              ],
              "bit_rate_audio":[
                {"audio_meta":{"bitrate":64000,"url_list":{"main_url":"https://cdn.example/64.m4a"}}},
                {"audio_meta":{"bitrate":192000,"url_list":{"main_url":"https://cdn.example/192.m4a","backup_url":"https://backup.example/192.m4a"}}}
              ]
            }
            """.trimIndent(),
        )

        val variants = DouyinMediaNormalizer.extractVideoVariants(video)

        assertEquals(2, variants.size)
        assertEquals("https://cdn.example/4k.mp4", variants[0].urls[0])
        assertEquals(80_000_000L, variants[0].size)
        assertEquals("api", variants[0].sizeSource)
        assertEquals("https://cdn.example/1080-high.mp4", variants[1].urls[0])
        assertEquals(10_000_000L, variants[1].size)
        assertEquals("estimated", variants[1].sizeSource)
        assertEquals(
            listOf("https://cdn.example/192.m4a", "https://backup.example/192.m4a"),
            DouyinMediaNormalizer.extractAudioUrls(video),
        )
    }
}
