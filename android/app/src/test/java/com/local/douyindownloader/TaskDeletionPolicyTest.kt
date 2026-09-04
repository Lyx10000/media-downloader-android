package com.local.douyindownloader

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDeletionPolicyTest {
    @Test
    fun removesTaskFolderWhenOnlyEmptyNestedMediaDirectoriesRemain() {
        val root = Files.createTempDirectory("task-delete-empty").toFile()
        root.resolve("media/nested").mkdirs()

        assertTrue(pruneEmptyDirectoryTree(root))
        assertFalse(root.exists())
    }

    @Test
    fun keepsTaskFolderWhenNestedDirectoryContainsUntrackedFile() {
        val root = Files.createTempDirectory("task-delete-retain").toFile()
        val file = root.resolve("media/user-file.txt")
        requireNotNull(file.parentFile).mkdirs()
        file.writeText("保留")
        try {
            assertFalse(pruneEmptyDirectoryTree(root))
            assertTrue(file.isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
