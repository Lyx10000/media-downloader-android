package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.MuxTrack
import com.local.multiplatformdownloader.core.download.VideoAudioSource
import com.local.multiplatformdownloader.core.download.chooseVideoAudioSource
import com.local.multiplatformdownloader.core.download.nextMuxTrack


import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDownloadPolicyTest {
    @Test
    fun embeddedAudioWinsWhenAnExternalAudioUrlIsAlsoAdvertised() {
        assertEquals(
            VideoAudioSource.EMBEDDED,
            chooseVideoAudioSource(hasEmbeddedAudio = true, hasSeparateAudio = true),
        )
    }

    @Test
    fun separateAudioIsUsedOnlyWhenTheVideoIsActuallySilent() {
        assertEquals(
            VideoAudioSource.SEPARATE,
            chooseVideoAudioSource(hasEmbeddedAudio = false, hasSeparateAudio = true),
        )
    }

    @Test
    fun missingAudioIsReportedWhenNeitherSourceContainsIt() {
        assertEquals(
            VideoAudioSource.MISSING,
            chooseVideoAudioSource(hasEmbeddedAudio = false, hasSeparateAudio = false),
        )
    }

    @Test
    fun muxChoosesTheSampleWithTheEarlierPresentationTime() {
        assertEquals(MuxTrack.AUDIO, nextMuxTrack(videoPtsUs = 33_333, audioPtsUs = 23_219))
        assertEquals(MuxTrack.VIDEO, nextMuxTrack(videoPtsUs = 33_333, audioPtsUs = 46_439))
    }

    @Test
    fun muxContinuesTheRemainingTrackAfterTheOtherReachesEndOfStream() {
        assertEquals(MuxTrack.AUDIO, nextMuxTrack(videoPtsUs = -1, audioPtsUs = 46_439))
        assertEquals(MuxTrack.VIDEO, nextMuxTrack(videoPtsUs = 66_666, audioPtsUs = -1))
        assertEquals(MuxTrack.NONE, nextMuxTrack(videoPtsUs = -1, audioPtsUs = -1))
    }
}
