package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadUrlPolicyTest {
    @Test
    fun upgradesOnlyTrustedXiaohongshuCdnHosts() {
        assertEquals(
            "https://sns-video-v2.xhscdn.com/video.mp4?token=abc",
            secureDownloadUrl("http://sns-video-v2.xhscdn.com/video.mp4?token=abc"),
        )
        assertEquals(
            "https://sns-bak-v1.xhscdn.com/video.mp4",
            secureDownloadUrl("http://sns-bak-v1.xhscdn.com/video.mp4"),
        )
        assertEquals(
            "http://xhscdn.com.evil.example/video.mp4",
            secureDownloadUrl("http://xhscdn.com.evil.example/video.mp4"),
        )
        assertEquals(
            "http://example.com/video.mp4",
            secureDownloadUrl("http://example.com/video.mp4"),
        )
    }
}
