package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTypesTest {
    @Test
    fun persistedValuesRemainBackwardCompatible() {
        assertEquals("merge_keep", DownloadMode.MERGE_KEEP.wireValue)
        assertEquals("tracks", DownloadMode.TRACKS.wireValue)
        assertEquals("video_only", DownloadMode.VIDEO_ONLY.wireValue)
        assertEquals("audio_only", DownloadMode.AUDIO_ONLY.wireValue)
        assertEquals("SAF", StorageMode.SAF.wireValue)
        assertEquals("AVAILABLE", FileState.AVAILABLE.wireValue)
        assertEquals("COMPLETE", TaskStatus.COMPLETE.wireValue)
        assertEquals("image", MediaKind.IMAGE.wireValue)
    }

    @Test
    fun legacyAndUnknownValuesUseSafeMappings() {
        assertEquals(DownloadMode.MERGE_KEEP, DownloadMode.fromWire("unexpected"))
        assertEquals(StorageMode.LEGACY, StorageMode.fromWire("unexpected"))
        assertEquals(FileState.UNKNOWN, FileState.fromWire("unexpected"))
        assertEquals(MediaKind.VIDEO, MediaKind.fromWire("unexpected"))
        assertEquals("FUTURE_STATUS", TaskStatus.fromWire("FUTURE_STATUS").wireValue)
    }
}
