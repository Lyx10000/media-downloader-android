package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskPreviewTest {
    @Test
    fun `image preview wins over video and audio`() {
        val audio = TaskOutput("audio", "track.mp3", "audio/mpeg")
        val video = TaskOutput("video", "clip.mp4", "video/mp4")
        val image = TaskOutput("image", "cover.jpg", "image/jpeg")

        assertEquals(
            image to TaskPreviewKind.IMAGE,
            selectTaskPreview(listOf(audio, video, image)),
        )
    }

    @Test
    fun `video preview wins when no image exists`() {
        val audio = TaskOutput("audio", "track.mp3", "audio/mpeg")
        val video = TaskOutput("video", "clip.mp4", "video/mp4")

        assertEquals(
            video to TaskPreviewKind.VIDEO,
            selectTaskPreview(listOf(audio, video)),
        )
    }

    @Test
    fun `unreadable output is skipped`() {
        val missingImage = TaskOutput("missing", "cover.jpg", "image/jpeg")
        val audio = TaskOutput("audio", "track.mp3", "audio/mpeg")

        assertEquals(
            audio to TaskPreviewKind.AUDIO,
            selectTaskPreview(
                outputs = listOf(missingImage, audio),
                isReadable = { it.uri != "missing" },
            ),
        )
    }

    @Test
    fun `unsupported outputs have no preview`() {
        assertNull(
            selectTaskPreview(
                listOf(TaskOutput("archive", "download.zip", "application/zip")),
            ),
        )
    }

    @Test
    fun `playback time formats minutes and seconds`() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("1:05", formatPlaybackTime(65_999))
        assertEquals("61:01", formatPlaybackTime(3_661_000))
    }
}
