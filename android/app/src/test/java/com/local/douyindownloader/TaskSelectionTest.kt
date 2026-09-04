package com.local.douyindownloader

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
    fun zhihuVideoUsesContentTitleAndKeepsAuthorAsSeparateIdentity() {
        val result = ParseResult(
            ok = true,
            platform = SourcePlatform.ZHIHU,
            kind = MediaKind.VIDEO,
            author = "十六盘点",
            description = "盘点视频",
        )

        assertEquals("盘点视频", taskDisplayTitle("十六盘点", result))
        assertEquals("旧标题", taskDisplayTitle("旧标题", result.copy(description = "")))
        assertEquals("博主", taskDisplayTitle("博主", result.copy(platform = SourcePlatform.DOUYIN)))
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
