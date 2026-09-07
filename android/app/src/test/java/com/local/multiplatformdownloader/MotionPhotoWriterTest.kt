package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.MotionPhotoWriter


import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoWriterTest {
    @Test
    fun `writer keeps source files and appends mp4 with motion metadata`() {
        val folder = Files.createTempDirectory("motion-photo").toFile()
        try {
            val image = File(folder, "source.jpg").apply {
                writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            }
            val videoBytes = byteArrayOf(0, 0, 0, 16, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 1, 2, 3, 4)
            val video = File(folder, "source.mp4").apply { writeBytes(videoBytes) }
            val output = File(folder, "motion.jpg")

            MotionPhotoWriter.compose(image, video, output)

            val result = output.readBytes()
            val text = result.toString(Charsets.ISO_8859_1)
            assertTrue(text.contains("GCamera:MotionPhoto=\"1\""))
            assertTrue(text.contains("Item:Length=\"${videoBytes.size}\""))
            assertTrue(result.takeLast(videoBytes.size).toByteArray().contentEquals(videoBytes))
            assertTrue(image.exists())
            assertTrue(video.exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `writer rejects non jpeg images`() {
        val folder = Files.createTempDirectory("motion-photo-invalid").toFile()
        try {
            val image = File(folder, "source.webp").apply { writeBytes("RIFF".toByteArray()) }
            val video = File(folder, "source.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 8, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())) }

            MotionPhotoWriter.compose(image, video, File(folder, "motion.jpg"))
        } finally {
            folder.deleteRecursively()
        }
    }
}
