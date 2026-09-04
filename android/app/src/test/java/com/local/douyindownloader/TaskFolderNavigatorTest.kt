package com.local.douyindownloader

import org.junit.Assert.assertEquals
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
}
