package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.feature.creator.BatchWorkEntity
import com.local.multiplatformdownloader.feature.creator.CreatorWork
import com.local.multiplatformdownloader.feature.creator.DownloadBatchEntity
import com.local.multiplatformdownloader.feature.creator.actionableCreatorBatches
import com.local.multiplatformdownloader.feature.creator.creatorKey


import org.junit.Assert.*
import org.junit.Test

class CreatorPausedPreparationTest {
    private fun entry(batch: String, status: String) = BatchWorkEntity(batch, "douyin:1", status, "", "")
    private fun batch(id: String, created: Long, status: String) =
        DownloadBatchEntity(id, "douyin:author", created, status, "{}", 1)
    private fun work(status: String) = CreatorWork("douyin:1", "douyin:author", SourcePlatform.DOUYIN,
        "1", "https://www.douyin.com/video/1", MediaKind.VIDEO, "作品",
        preparation = entry("old", status))

    @Test fun pausedAndFailedRecordsAllowSelectionDeletionAndRetry() {
        for (status in listOf("FAILED", "PAUSED")) {
            assertTrue(work(status).hasLocalRecord)
            assertTrue(work(status).preparationActionable)
        }
        assertFalse(work("PAUSED").preparationFailed)
    }

    @Test fun livePreparationAndAlreadyScheduledRecordsCannotBeDeletedAsInactive() {
        for (status in listOf("QUEUED", "PARSING", "PARSING_HTTP", "WEB_REQUIRED", "PREPARED", "SCHEDULED")) {
            assertFalse(work(status).preparationActionable)
        }
        val linked = work("PAUSED").copy(preparation = entry("old", "PAUSED").copy(taskId = "actual-task"))
        assertFalse(linked.preparationActionable)
        assertFalse(work("PAUSED").copy(preparation = null).preparationActionable)
    }

    @Test fun completedNewBatchDoesNotHideOldPausedWorks() {
        val old = batch("old", 1, "PAUSED")
        val latest = batch("new", 2, "SCHEDULED")
        assertEquals(old, actionableCreatorBatches(listOf(latest, old),
            listOf(entry("new", "SCHEDULED"), entry("old", "PAUSED"))).single())
    }

    @Test fun emptyPausedBatchDoesNotHideAnOlderResumableBatch() {
        val old = batch("old", 1, "PAUSED")
        val empty = batch("empty", 2, "PAUSED")
        assertEquals(old, actionableCreatorBatches(listOf(empty, old), listOf(entry("old", "PAUSED"))).single())
    }

    @Test fun activeBatchStaysVisibleAndAuthorsAreIndependent() {
        val old = batch("old", 1, "PAUSED")
        val active = batch("active", 2, "QUEUED")
        val other = batch("other", 3, "PAUSED").copy(creatorKey = "zhihu:someone")
        val result = actionableCreatorBatches(listOf(other, active, old),
            listOf(entry("old", "PAUSED"), entry("other", "WEB_REQUIRED")))
        assertEquals(listOf(other, active), result)
    }
}
