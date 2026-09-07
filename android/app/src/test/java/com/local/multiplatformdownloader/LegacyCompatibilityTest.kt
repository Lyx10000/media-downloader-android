package com.local.multiplatformdownloader


import com.local.multiplatformdownloader.core.compat.LegacyCompatibility
import com.local.multiplatformdownloader.core.compat.ProductIdentity
import com.local.multiplatformdownloader.core.compat.matchesPersistedWorkerClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCompatibilityTest {
    @Test
    fun `current and legacy identities remain deliberately distinct`() {
        assertEquals("MultiPlatformDownloader", ProductIdentity.DEFAULT_DOWNLOAD_DIRECTORY)
        assertEquals("com.local.douyindownloader", LegacyCompatibility.PUBLISHED_APPLICATION_ID)
        assertEquals("DouyinDownloader", LegacyCompatibility.DOWNLOAD_DIRECTORY)
        assertNotEquals(ProductIdentity.DEFAULT_DOWNLOAD_DIRECTORY, LegacyCompatibility.DOWNLOAD_DIRECTORY)
    }

    @Test
    fun `storage lookup checks current root before legacy root`() {
        assertEquals(
            listOf("MultiPlatformDownloader", "DouyinDownloader"),
            LegacyCompatibility.readableDownloadDirectories,
        )
        assertTrue(LegacyCompatibility.readableDownloadDirectories.distinct().size == 2)
    }

    @Test
    fun `published worker names resolve after namespace migration`() {
        assertTrue(
            matchesPersistedWorkerClass(
                persistedClassName = "com.local.douyindownloader.DownloadWorker",
                currentClassName = "com.local.multiplatformdownloader.feature.download.DownloadWorker",
                simpleName = "DownloadWorker",
            ),
        )
        assertTrue(
            matchesPersistedWorkerClass(
                persistedClassName = "com.local.multiplatformdownloader.CreatorBatchWorker",
                currentClassName = "com.local.multiplatformdownloader.feature.creator.CreatorBatchWorker",
                simpleName = "CreatorBatchWorker",
            ),
        )
    }
}
