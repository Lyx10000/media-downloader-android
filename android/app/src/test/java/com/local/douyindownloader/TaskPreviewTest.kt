package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskPreviewTest {
    @Test
    fun `mixed X media tracks share a stable attachment group`() {
        assertEquals("media_02", mixedMediaGroupKey("media_02_video.mp4"))
        assertEquals("media_02", mixedMediaGroupKey("media_02_video_audio.m4a"))
        assertEquals("media_03", mixedMediaGroupKey("media_03_gif.mp4"))
        assertNull(mixedMediaGroupKey("video_1.mp4"))
    }

    @Test
    fun documentPreviewIncludesImageGalleryAndEveryEmbeddedVideo() {
        val image = TaskOutput("image", "image_001.jpg", "image/jpeg", relativePath = "media/image_001.jpg")
        val cover = TaskOutput("cover", "video_001_cover.jpg", "image/jpeg", relativePath = "media/video_001_cover.jpg")
        val firstVideo = TaskOutput("video1", "video_001.mp4", "video/mp4", relativePath = "media/video_001.mp4")
        val secondVideo = TaskOutput("video2", "video_002.mp4", "video/mp4", relativePath = "media/video_002.mp4")
        val groups = documentPreviewGroups(listOf(image, cover, firstVideo, secondVideo))

        assertEquals(listOf(image), groups.images)
        assertEquals(listOf(firstVideo, secondVideo), groups.videos)
    }

    @Test
    fun `image preview wins over video and background audio`() {
        val audio = TaskOutput("audio", "track.mp3", "audio/mpeg")
        val video = TaskOutput("video", "clip.mp4", "video/mp4")
        val image = TaskOutput("image", "cover.jpg", "image/jpeg")

        val selected = selectPreviewOutput(listOf(audio, video, image))

        assertEquals(image, selected?.output)
        assertEquals(TaskPreviewKind.IMAGE, selected?.kind)
        assertEquals(1, selected?.matchingOutputCount)
        assertEquals(listOf(image), selected?.imageOutputs)
    }

    @Test
    fun `image preview keeps every image in output order`() {
        val first = TaskOutput("image", "image_1.jpg", "image/jpeg")
        val ignored = TaskOutput("metadata", "details.json", "application/json")
        val second = TaskOutput("image", "image_2.webp", "image/webp")

        val selected = selectPreviewOutput(listOf(first, ignored, second))

        assertEquals(first, selected?.output)
        assertEquals(2, selected?.matchingOutputCount)
        assertEquals(listOf(first, second), selected?.imageOutputs)
    }

    @Test
    fun `complete video is preferred over separate tracks`() {
        val videoTrack = TaskOutput("video", "video_1_video.mp4", "video/mp4")
        val audioTrack = TaskOutput("audio", "video_1_audio.m4a", "audio/mp4")
        val completeVideo = TaskOutput("merged", "video_1.mp4", "video/mp4")
        val tracks = mapOf(
            videoTrack to MediaTrackInfo(hasVideo = true, hasAudio = false),
            completeVideo to MediaTrackInfo(hasVideo = true, hasAudio = true),
        )

        val selected = selectPreviewOutput(
            listOf(videoTrack, audioTrack, completeVideo),
            tracksFor = tracks::get,
        )

        assertEquals(completeVideo, selected?.output)
        assertEquals(VideoAudioMode.EMBEDDED, selected?.videoAudioMode)
        assertNull(selected?.audioOutput)
    }

    @Test
    fun `separate video and audio are paired`() {
        val video = TaskOutput("video", "video_1_video.mp4", "video/mp4")
        val audio = TaskOutput("audio", "video_1_audio.m4a", "audio/mp4")

        val selected = selectPreviewOutput(
            listOf(video, audio),
            tracksFor = { output ->
                if (output == video) MediaTrackInfo(hasVideo = true, hasAudio = false) else null
            },
        )

        assertEquals(video, selected?.output)
        assertEquals(audio, selected?.audioOutput)
        assertEquals(VideoAudioMode.SEPARATE, selected?.videoAudioMode)
    }

    @Test
    fun `video without audio is marked silent`() {
        val video = TaskOutput("video", "video_1_video.mp4", "video/mp4")

        val selected = selectPreviewOutput(
            listOf(video),
            tracksFor = { MediaTrackInfo(hasVideo = true, hasAudio = false) },
        )

        assertEquals(video, selected?.output)
        assertEquals(VideoAudioMode.SILENT, selected?.videoAudioMode)
    }

    @Test
    fun `pure audio remains playable`() {
        val audio = TaskOutput("audio", "track.mp3", "audio/mpeg")

        val selected = selectPreviewOutput(listOf(audio))

        assertEquals(audio, selected?.output)
        assertEquals(TaskPreviewKind.AUDIO, selected?.kind)
    }

    @Test
    fun `legacy complete filename is preferred when track inspection is unavailable`() {
        val separateVideo = TaskOutput("video", "video_1_video.mp4", "video/mp4")
        val completeVideo = TaskOutput("merged", "video_1.mp4", "video/mp4")

        val selected = selectPreviewOutput(listOf(separateVideo, completeVideo))

        assertEquals(completeVideo, selected?.output)
        assertEquals(VideoAudioMode.EMBEDDED, selected?.videoAudioMode)
    }

    @Test
    fun `unsupported outputs have no preview`() {
        assertNull(
            selectPreviewOutput(
                listOf(TaskOutput("archive", "download.zip", "application/zip")),
            ),
        )
    }

    @Test
    fun `markdown without media is identified as text only`() {
        val outputs = listOf(
            TaskOutput("document", "answer.md", "text/markdown"),
            TaskOutput("metadata", "details.json", "application/json"),
        )

        assertEquals(true, isTextOnlyPreview(outputs))
    }

    @Test
    fun `declared media prevents text only classification`() {
        val outputs = listOf(
            TaskOutput("document", "answer.md", "text/markdown"),
            TaskOutput("image", "image_001.jpg", "image/jpeg", relativePath = "media/image_001.jpg"),
        )

        assertEquals(false, isTextOnlyPreview(outputs))
    }

    @Test
    fun `playback time formats minutes and seconds`() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("1:05", formatPlaybackTime(65_999))
        assertEquals("61:01", formatPlaybackTime(3_661_000))
    }
}
