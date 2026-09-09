package com.local.multiplatformdownloader.feature.tasks

import android.content.Context
import android.net.Uri
import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.feature.preview.MediaPreviewCoordinator
import com.local.multiplatformdownloader.feature.preview.MediaPreviewState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TaskInteractionCoordinator @Inject constructor(
    private val store: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val shareCoordinator: ShareCoordinator,
    private val mediaPreviewCoordinator: MediaPreviewCoordinator,
    private val taskPreviewResolver: TaskPreviewResolver,
    private val taskFolderNavigator: TaskFolderNavigator,
    private val taskFileOperationCoordinator: TaskFileOperationCoordinator,
) {
    private val _expandedTaskId = MutableStateFlow<String?>(null)
    val expandedTaskId: StateFlow<String?> = _expandedTaskId.asStateFlow()

    val mediaPreviewState: StateFlow<MediaPreviewState> = mediaPreviewCoordinator.state

    private val _fullscreenTaskId = MutableStateFlow<String?>(null)
    val fullscreenTaskId: StateFlow<String?> = _fullscreenTaskId.asStateFlow()

    private val _fileOperationTaskId = MutableStateFlow<String?>(null)
    val fileOperationTaskId: StateFlow<String?> = _fileOperationTaskId.asStateFlow()

    suspend fun share(context: Context, taskId: String, files: List<ShareableFile>): String =
        shareCoordinator.share(context, taskId, files).message

    fun togglePreview(taskId: String) {
        val current = _expandedTaskId.value
        if (current == taskId) {
            if (_fullscreenTaskId.value == taskId) _fullscreenTaskId.value = null
            mediaPreviewCoordinator.stopIfTask(taskId)
            _expandedTaskId.value = null
        } else {
            _fullscreenTaskId.value = null
            mediaPreviewCoordinator.stopAndRelease("PREVIEW_SWITCHED")
            _expandedTaskId.value = taskId
        }
    }

    suspend fun resolvePreview(
        taskId: String,
        outputs: List<TaskOutput>,
    ): List<TaskPreviewMedia> = taskPreviewResolver.resolve(taskId, outputs)

    fun toggleMedia(taskId: String, media: TaskPreviewMedia) {
        if (_expandedTaskId.value != taskId) _expandedTaskId.value = taskId
        mediaPreviewCoordinator.toggle(taskId, media)
    }

    fun seek(taskId: String, positionMs: Long) {
        mediaPreviewCoordinator.seekTo(taskId, positionMs)
    }

    fun toggleMute(taskId: String) {
        mediaPreviewCoordinator.toggleMute(taskId)
    }

    fun enterFullscreen(taskId: String, media: TaskPreviewMedia) {
        if (mediaPreviewState.value.taskId != taskId ||
            !mediaPreviewState.value.source.samePreviewSource(media)
        ) {
            mediaPreviewCoordinator.toggle(taskId, media)
        }
        _fullscreenTaskId.value = taskId
    }

    fun exitFullscreen() {
        if (_fullscreenTaskId.value == null) return
        mediaPreviewCoordinator.pause("FULLSCREEN_EXITED")
        _fullscreenTaskId.value = null
    }

    fun stopPreviewIfTask(taskId: String, reason: String = "PREVIEW_DISPOSED") {
        if (_fullscreenTaskId.value != taskId) {
            mediaPreviewCoordinator.stopIfTask(taskId, reason)
        }
    }

    suspend fun openTaskFolder(context: Context, task: TaskRecord): String {
        val spec = store.getSpec(task.id) ?: return "无法读取该任务的保存目录"
        return taskFolderNavigator.open(context, task.id, spec).message
    }

    fun onFileManagerOpened(taskId: String) {
        mediaPreviewCoordinator.stopIfTask(taskId, "FILE_MANAGER_OPENED")
        runCatching { logger.event(taskId, "FILE_MANAGER", "FILE_MANAGER_OPENED") }
    }

    suspend fun describeFiles(outputs: List<TaskOutput>): List<ManagedFileItem> =
        taskFileOperationCoordinator.describe(outputs)

    fun beginFileOperation(taskId: String): Boolean {
        if (_fileOperationTaskId.value != null) return false
        _fileOperationTaskId.value = taskId
        mediaPreviewCoordinator.stopIfTask(taskId, "FILE_OPERATION_REQUESTED")
        return true
    }

    fun finishFileOperation() {
        _fileOperationTaskId.value = null
    }

    suspend fun rename(taskId: String, outputUri: String, requestedBase: String) =
        taskFileOperationCoordinator.rename(taskId, outputUri, requestedBase)

    suspend fun delete(taskId: String, outputUris: Set<String>) =
        taskFileOperationCoordinator.delete(taskId, outputUris)

    suspend fun transfer(
        taskId: String,
        outputUris: Set<String>,
        destinationTree: Uri,
        mode: ManagedTransferMode,
    ) = taskFileOperationCoordinator.transfer(taskId, outputUris, destinationTree, mode)

    fun closeTask(taskId: String, reason: String) {
        if (_expandedTaskId.value != taskId) return
        if (_fullscreenTaskId.value == taskId) _fullscreenTaskId.value = null
        mediaPreviewCoordinator.stopIfTask(taskId, reason)
        _expandedTaskId.value = null
    }

    fun reconcileTasks(records: List<TaskRecord>) {
        val expanded = _expandedTaskId.value ?: return
        if (records.none { it.id == expanded }) {
            closeTask(expanded, "TASK_REMOVED")
        }
    }

    fun pause(reason: String) {
        mediaPreviewCoordinator.pause(reason)
    }

    fun stopAndRelease(reason: String = "PREVIEW_STOPPED") {
        _fullscreenTaskId.value = null
        mediaPreviewCoordinator.stopAndRelease(reason)
    }
}

private fun TaskPreviewMedia?.samePreviewSource(other: TaskPreviewMedia): Boolean =
    this?.uri == other.uri
