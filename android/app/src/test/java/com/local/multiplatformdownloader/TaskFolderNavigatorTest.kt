package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.feature.tasks.DIRECTORY_VIEW_GRANT_FLAGS
import com.local.multiplatformdownloader.feature.tasks.defaultDirectoryDocumentId
import com.local.multiplatformdownloader.feature.tasks.legacyDirectoryDocumentIds


import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskFolderNavigatorTest {
    @Test
    fun `default root document id is stable`() {
        assertEquals(
            "primary:Download/MultiPlatformDownloader",
            defaultDirectoryDocumentId(null),
        )
    }

    @Test
    fun `task folder is appended to default document id`() {
        assertEquals(
            "primary:Download/MultiPlatformDownloader/2026-09-04_12-30-00_abcdef12",
            defaultDirectoryDocumentId("2026-09-04_12-30-00_abcdef12"),
        )
    }

    @Test
    fun `legacy document ids remain available for existing tasks`() {
        assertEquals(
            listOf("primary:Download/DouyinDownloader/legacy-task"),
            legacyDirectoryDocumentIds("legacy-task"),
        )
    }

    @Test
    fun `directory view grants read write and descendant access`() {
        assertNotEquals(0, DIRECTORY_VIEW_GRANT_FLAGS and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotEquals(0, DIRECTORY_VIEW_GRANT_FLAGS and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotEquals(0, DIRECTORY_VIEW_GRANT_FLAGS and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }
}
