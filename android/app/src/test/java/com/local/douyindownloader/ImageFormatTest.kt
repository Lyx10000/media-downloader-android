package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFormatTest {
    @Test
    fun detectsCommonImageFormatsFromContentInsteadOfUrl() {
        assertEquals("jpg", imageExtension(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), "bin"))
        assertEquals(
            "png",
            imageExtension(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                "bin",
            ),
        )
        assertEquals("gif", imageExtension("GIF89a".toByteArray(), "bin"))
        assertEquals("webp", imageExtension("RIFF0000WEBP".toByteArray(), "bin"))
        assertEquals("avif", imageExtension("0000ftypavif".toByteArray(), "bin"))
        assertEquals("jpg", imageExtension("unknown".toByteArray(), "jpg"))
    }
}
