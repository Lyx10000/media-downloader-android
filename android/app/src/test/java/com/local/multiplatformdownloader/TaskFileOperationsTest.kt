package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.feature.tasks.ManagedFileGateway
import com.local.multiplatformdownloader.feature.tasks.ManagedFileItem
import com.local.multiplatformdownloader.feature.tasks.ManagedTransferMode
import com.local.multiplatformdownloader.feature.tasks.canShareManagedOutputs
import com.local.multiplatformdownloader.feature.tasks.executeManagedTransfer
import com.local.multiplatformdownloader.feature.tasks.renamedDisplayName
import com.local.multiplatformdownloader.feature.tasks.replaceTaskOutput
import com.local.multiplatformdownloader.feature.tasks.uniqueDisplayName


import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskFileOperationsTest {
    @Test
    fun `rename preserves the original extension`() {
        assertEquals("新名称.mp4", renamedDisplayName("video.mp4", " 新名称 "))
        assertEquals("新名称", renamedDisplayName("README", "新名称"))
    }

    @Test
    fun `rename rejects unsafe names`() {
        assertNull(renamedDisplayName("video.mp4", ""))
        assertNull(renamedDisplayName("video.mp4", "../escape"))
        assertNull(renamedDisplayName("video.mp4", "."))
        assertNull(renamedDisplayName("video.mp4", "a\\b"))
    }

    @Test
    fun `unique destination names never overwrite existing files`() {
        val existing = setOf("photo.jpg", "photo (1).jpg", "photo (2).jpg")

        assertEquals("photo (3).jpg", uniqueDisplayName("photo.jpg", existing::contains))
        assertEquals("track", uniqueDisplayName("track") { false })
    }

    @Test
    fun `replacement updates only the matching output and keeps order`() {
        val first = TaskOutput("content://source/1", "one.jpg", "image/jpeg", 10)
        val second = TaskOutput("content://source/2", "two.jpg", "image/jpeg", 20)
        val moved = second.copy(uri = "content://target/2", displayName = "two (1).jpg")

        val updated = replaceTaskOutput(listOf(first, second), second.uri, moved)

        assertEquals(listOf(first, moved), updated)
    }

    @Test
    fun `share selection accepts one media family only`() {
        val images = listOf(
            TaskOutput("content://1", "one.jpg", "image/jpeg"),
            TaskOutput("content://2", "two.png", "image/png"),
        )
        val mixed = images + TaskOutput("content://3", "clip.mp4", "video/mp4")

        assertTrue(canShareManagedOutputs(images))
        assertFalse(canShareManagedOutputs(mixed))
        assertFalse(canShareManagedOutputs(emptyList()))
    }

    @Test
    fun `move replaces source only after source deletion succeeds`() {
        val source = TaskOutput("content://source/1", "clip.mp4", "video/mp4", 100)
        val copied = source.copy(uri = "content://target/1")
        val gateway = FakeManagedFileGateway(copied, sourceDeleteSucceeds = true)

        val result = executeManagedTransfer(
            gateway,
            source,
            "content://target/tree",
            ManagedTransferMode.MOVE,
        )

        assertTrue(result.success)
        assertEquals(copied, result.replacement)
        assertEquals(listOf(source), gateway.deleted)
    }

    @Test
    fun `copy leaves the source and task output unchanged`() {
        val source = TaskOutput("content://source/1", "clip.mp4", "video/mp4", 100)
        val copied = source.copy(uri = "content://target/1")
        val gateway = FakeManagedFileGateway(copied, sourceDeleteSucceeds = true)

        val result = executeManagedTransfer(
            gateway,
            source,
            "content://target/tree",
            ManagedTransferMode.COPY,
        )

        assertTrue(result.success)
        assertNull(result.replacement)
        assertTrue(gateway.deleted.isEmpty())
    }

    @Test
    fun `failed source deletion rolls back copied move target`() {
        val source = TaskOutput("content://source/1", "clip.mp4", "video/mp4", 100)
        val copied = source.copy(uri = "content://target/1")
        val gateway = FakeManagedFileGateway(copied, sourceDeleteSucceeds = false)

        val result = executeManagedTransfer(
            gateway,
            source,
            "content://target/tree",
            ManagedTransferMode.MOVE,
        )

        assertFalse(result.success)
        assertNull(result.replacement)
        assertEquals(listOf(source, copied), gateway.deleted)
    }

    @Test
    fun `failed move reports an untracked copy when rollback also fails`() {
        val source = TaskOutput("content://source/1", "clip.mp4", "video/mp4", 100)
        val copied = source.copy(uri = "content://target/1")
        val gateway = FakeManagedFileGateway(
            copied,
            sourceDeleteSucceeds = false,
            rollbackDeleteSucceeds = false,
        )

        val result = executeManagedTransfer(
            gateway,
            source,
            "content://target/tree",
            ManagedTransferMode.MOVE,
        )

        assertFalse(result.success)
        assertTrue(result.rollbackFailed)
        assertTrue(result.error.contains("目标可能留有副本"))
    }

    private class FakeManagedFileGateway(
        private val copied: TaskOutput,
        private val sourceDeleteSucceeds: Boolean,
        private val rollbackDeleteSucceeds: Boolean = true,
    ) : ManagedFileGateway {
        val deleted = mutableListOf<TaskOutput>()

        override fun describe(output: TaskOutput) = ManagedFileItem(output, Uri.parse(output.uri), true)

        override fun rename(output: TaskOutput, newDisplayName: String) =
            output.copy(displayName = newDisplayName)

        override fun copy(output: TaskOutput, destinationTree: String) = copied

        override fun delete(output: TaskOutput): Boolean {
            deleted += output
            return if (output == copied) rollbackDeleteSucceeds else sourceDeleteSucceeds
        }
    }
}
