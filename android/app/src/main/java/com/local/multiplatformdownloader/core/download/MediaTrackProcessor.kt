package com.local.multiplatformdownloader.core.download

import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object MediaTrackProcessor {
    fun extractVideo(source: File, output: File) = copySingleTrack(source, output, "video/")

    fun extractAudio(source: File, output: File) = copySingleTrack(source, output, "audio/")

    fun mux(videoSource: File, audioSource: File, output: File) {
        output.parentFile?.mkdirs()
        val video = selectedExtractor(videoSource, "video/")
        val audio = selectedExtractor(audioSource, "audio/")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoOutputTrack = muxer.addTrack(video.format)
            val audioOutputTrack = muxer.addTrack(audio.format)
            muxer.start()
            discardNegativeSamples(video.extractor)
            discardNegativeSamples(audio.extractor)
            copyInterleavedSamples(
                video = video,
                videoOutputTrack = videoOutputTrack,
                audio = audio,
                audioOutputTrack = audioOutputTrack,
                muxer = muxer,
            )
        } finally {
            video.extractor.release()
            audio.extractor.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    fun probe(source: File): List<Map<String, Any>> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            (0 until extractor.trackCount).map { index ->
                val format = extractor.getTrackFormat(index)
                buildMap {
                    put("index", index)
                    put("mime", format.getString(MediaFormat.KEY_MIME).orEmpty())
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) put("width", format.getInteger(MediaFormat.KEY_WIDTH))
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) put("height", format.getInteger(MediaFormat.KEY_HEIGHT))
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) put("bitrate", format.getInteger(MediaFormat.KEY_BIT_RATE))
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun copySingleTrack(source: File, output: File, mimePrefix: String) {
        output.parentFile?.mkdirs()
        val selected = selectedExtractor(source, mimePrefix)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val outputTrack = muxer.addTrack(selected.format)
            muxer.start()
            discardNegativeSamples(selected.extractor)
            copySamples(selected.extractor, outputTrack, muxer, selected.format)
        } finally {
            selected.extractor.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun selectedExtractor(source: File, mimePrefix: String): SelectedTrack {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) {
                extractor.selectTrack(index)
                return SelectedTrack(extractor, format)
            }
        }
        extractor.release()
        error("源文件没有${if (mimePrefix == "audio/") "音频" else "视频"}轨")
    }

    private fun copySamples(
        extractor: MediaExtractor,
        outputTrack: Int,
        muxer: MediaMuxer,
        format: MediaFormat,
    ) {
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
        } else {
            8 * 1024 * 1024
        }
        val buffer = ByteBuffer.allocateDirect(maxSize)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            val sampleFlags = extractor.sampleFlags
            var codecFlags = 0
            if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            info.flags = codecFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun copyInterleavedSamples(
        video: SelectedTrack,
        videoOutputTrack: Int,
        audio: SelectedTrack,
        audioOutputTrack: Int,
        muxer: MediaMuxer,
    ) {
        val videoBuffer = ByteBuffer.allocateDirect(maxInputSize(video.format))
        val audioBuffer = ByteBuffer.allocateDirect(maxInputSize(audio.format))
        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        while (true) {
            val wroteSample = when (
                nextMuxTrack(video.extractor.sampleTime, audio.extractor.sampleTime)
            ) {
                MuxTrack.VIDEO -> writeCurrentSample(
                    video.extractor,
                    videoOutputTrack,
                    muxer,
                    videoBuffer,
                    videoInfo,
                )
                MuxTrack.AUDIO -> writeCurrentSample(
                    audio.extractor,
                    audioOutputTrack,
                    muxer,
                    audioBuffer,
                    audioInfo,
                )
                MuxTrack.NONE -> return
            }
            if (!wroteSample) return
        }
    }

    private fun writeCurrentSample(
        extractor: MediaExtractor,
        outputTrack: Int,
        muxer: MediaMuxer,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ): Boolean {
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) return false
        info.set(0, size, extractor.sampleTime, codecFlags(extractor.sampleFlags))
        muxer.writeSampleData(outputTrack, buffer, info)
        extractor.advance()
        return true
    }

    private fun discardNegativeSamples(extractor: MediaExtractor) {
        while (extractor.sampleTime != -1L && extractor.sampleTime < 0L) {
            extractor.advance()
        }
    }

    private fun maxInputSize(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024)
        } else {
            8 * 1024 * 1024
        }

    private fun codecFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private data class SelectedTrack(
        val extractor: MediaExtractor,
        val format: MediaFormat,
    )
}
