package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.secureDownloadUrl

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadUrlPolicyTest {
    @Test
    fun upgradesOnlyTrustedPlatformCdnHosts() {
        assertEquals(
            "https://sns-video-v2.xhscdn.com/video.mp4?token=abc",
            secureDownloadUrl("http://sns-video-v2.xhscdn.com/video.mp4?token=abc"),
        )
        assertEquals(
            "https://sns-bak-v1.xhscdn.com/video.mp4",
            secureDownloadUrl("http://sns-bak-v1.xhscdn.com/video.mp4"),
        )
        assertEquals(
            "https://vdn3.vzuu.com/video.mp4",
            secureDownloadUrl("http://vdn3.vzuu.com/video.mp4"),
        )
        assertEquals(
            "https://picx.zhimg.com/image.jpg",
            secureDownloadUrl("http://picx.zhimg.com/image.jpg"),
        )
        assertEquals(
            "http://xhscdn.com.evil.example/video.mp4",
            secureDownloadUrl("http://xhscdn.com.evil.example/video.mp4"),
        )
        assertEquals(
            "http://example.com/video.mp4",
            secureDownloadUrl("http://example.com/video.mp4"),
        )
        assertEquals(
            "http://vzuu.com.evil.example/video.mp4",
            secureDownloadUrl("http://vzuu.com.evil.example/video.mp4"),
        )
    }
}
