package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.database.taskDisplayTitle
import com.local.multiplatformdownloader.core.model.FileState
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.feature.tasks.batchRedownloadSummary
import com.local.multiplatformdownloader.feature.tasks.isLocalContentTask
import com.local.multiplatformdownloader.feature.tasks.isTaskRedownloadEligible
import com.local.multiplatformdownloader.feature.tasks.isTaskQueueVisible
import com.local.multiplatformdownloader.feature.tasks.reconcileTaskSelection
import com.local.multiplatformdownloader.feature.tasks.toggleTaskSelection
import com.local.multiplatformdownloader.feature.home.accumulateAuthorTaskPeaks


import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSelectionTest {
    @Test
    fun togglesSelectedTaskIds() {
        assertEquals(setOf("a", "b"), toggleTaskSelection(setOf("a"), "b"))
        assertEquals(setOf("b"), toggleTaskSelection(setOf("a", "b"), "a"))
    }

    @Test
    fun dropsIdsThatAreNoLongerVisible() {
        assertEquals(setOf("b"), reconcileTaskSelection(setOf("a", "b"), setOf("b", "c")))
    }

    @Test
    fun onlyInactiveTasksCanBeRedownloaded() {
        assertEquals(true, isTaskRedownloadEligible(task(TaskStatus.COMPLETE)))
        assertEquals(true, isTaskRedownloadEligible(task(TaskStatus.CANCELLED)))
        assertEquals(false, isTaskRedownloadEligible(task(TaskStatus.RUNNING)))
        assertEquals(false, isTaskRedownloadEligible(task(TaskStatus.QUEUED)))
        assertEquals(
            false,
            isTaskRedownloadEligible(task(TaskStatus.FAILED, FileState.DELETE_FAILED)),
        )
    }

    @Test
    fun summarizesBatchRedownloadResults() {
        assertEquals(
            "已开始 2 个任务，1 个启动失败，1 个状态不允许重新下载",
            batchRedownloadSummary(started = 2, failed = 1, skipped = 1),
        )
    }

    @Test
    fun mediaTaskUsesContentCaptionAndKeepsAuthorAsSeparateIdentity() {
        val result = ParseResult(
            ok = true,
            platform = SourcePlatform.ZHIHU,
            kind = MediaKind.VIDEO,
            author = "十六盘点",
            description = "盘点视频",
        )

        assertEquals("盘点视频", taskDisplayTitle("十六盘点", result))
        assertEquals("旧标题", taskDisplayTitle("旧标题", result.copy(description = "")))
        assertEquals(
            "盘点视频",
            taskDisplayTitle("十六盘点", result.copy(platform = SourcePlatform.DOUYIN)),
        )
        assertEquals(
            "小红书作品文案",
            taskDisplayTitle(
                "小红书作者",
                result.copy(
                    platform = SourcePlatform.XIAOHONGSHU,
                    kind = MediaKind.IMAGE,
                    description = "小红书作品文案",
                ),
            ),
        )
    }

    @Test
    fun taskCaptionIsCollapsedToOneReadableLine() {
        val result = ParseResult(
            ok = true,
            platform = SourcePlatform.DOUYIN,
            kind = MediaKind.VIDEO,
            author = "作者",
            description = "第一行\n  第二行",
        )

        assertEquals("第一行 第二行", taskDisplayTitle("作者", result))
    }

    @Test
    fun completedTasksLeaveQueueButRemainInLocalLibrary() {
        val completed = task(TaskStatus.COMPLETE).copy(
            outputs = listOf(com.local.multiplatformdownloader.core.model.TaskOutput("content://file")),
        )

        assertEquals(false, isTaskQueueVisible(completed))
        assertEquals(true, isLocalContentTask(completed))
    }

    @Test
    fun partialFailedTaskRemainsInQueueAndLocalLibrary() {
        val partial = task(TaskStatus.FAILED, FileState.PARTIAL).copy(
            outputs = listOf(com.local.multiplatformdownloader.core.model.TaskOutput("content://partial")),
        )

        assertEquals(true, isTaskQueueVisible(partial))
        assertEquals(true, isLocalContentTask(partial))
    }

    @Test
    fun cancelledTaskWithoutFilesIsNotShownAnywhere() {
        val cancelled = task(TaskStatus.CANCELLED, FileState.UNKNOWN)

        assertEquals(false, isTaskQueueVisible(cancelled))
        assertEquals(false, isLocalContentTask(cancelled))
    }

    @Test
    fun shortTransferSampleStillUpdatesAuthorPeak() {
        val running = task(TaskStatus.RUNNING).copy(
            id = "short-image",
            authorKey = "douyin:author",
        )

        val updated = accumulateAuthorTaskPeaks(
            previous = emptyMap(),
            tasks = listOf(running),
            taskSpeeds = mapOf("short-image" to 2_400_000L),
        )

        assertEquals(2_400_000L, updated["douyin:author"])
    }

    private fun task(
        status: TaskStatus,
        fileState: FileState = FileState.AVAILABLE,
    ) = TaskRecord(
        id = "task",
        createdAt = 0,
        status = status,
        stage = "",
        progress = 0,
        title = "",
        outputs = emptyList(),
        error = "",
        fileState = fileState,
    )
}
