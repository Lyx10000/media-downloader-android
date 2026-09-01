package com.local.douyindownloader

internal enum class VideoAudioSource {
    EMBEDDED,
    SEPARATE,
    MISSING,
}

internal fun chooseVideoAudioSource(
    hasEmbeddedAudio: Boolean,
    hasSeparateAudio: Boolean,
): VideoAudioSource = when {
    hasEmbeddedAudio -> VideoAudioSource.EMBEDDED
    hasSeparateAudio -> VideoAudioSource.SEPARATE
    else -> VideoAudioSource.MISSING
}

internal enum class MuxTrack {
    VIDEO,
    AUDIO,
    NONE,
}

internal fun nextMuxTrack(videoPtsUs: Long, audioPtsUs: Long): MuxTrack = when {
    videoPtsUs == -1L && audioPtsUs == -1L -> MuxTrack.NONE
    videoPtsUs == -1L -> MuxTrack.AUDIO
    audioPtsUs == -1L -> MuxTrack.VIDEO
    videoPtsUs <= audioPtsUs -> MuxTrack.VIDEO
    else -> MuxTrack.AUDIO
}
