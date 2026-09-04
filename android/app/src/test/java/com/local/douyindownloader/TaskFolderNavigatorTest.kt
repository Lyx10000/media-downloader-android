package com.local.douyindownloader

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskFolderNavigatorTest {
    @Test
    fun `default root document id is stable`() {
        assertEquals(
            "primary:Download/DouyinDownloader",
            defaultDirectoryDocumentId(null),
        )
    }

    @Test
    fun `task folder is appended to default document id`() {
        assertEquals(
            "primary:Download/DouyinDownloader/2026-09-04_12-30-00_abcdef12",
            defaultDirectoryDocumentId("2026-09-04_12-30-00_abcdef12"),
        )
    }

    @Test
    fun `directory view grants read write and descendant access`() {
        assertNotEquals(0, DIRECTORY_VIEW_GRANT_FLAGS and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotEquals(0, DIRECTORY_VIEW_GRANT_FLAGS and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNotEquals(0, DIRECTORY_VIEW_GRANT_FLAGS and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }
}
