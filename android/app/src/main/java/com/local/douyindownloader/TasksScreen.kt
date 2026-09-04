package com.local.douyindownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
internal fun EmptyTasksStatus() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("还没有下载任务")
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TasksScreen(
    tasks: List<TaskRecord>,
    viewModel: MainViewModel,
    chooseFolder: () -> Unit,
    requestAllFilesAccess: () -> Unit,
) {
    val context = LocalContext.current
    val expandedTaskId by viewModel.expandedTaskId.collectAsStateWithLifecycle()
    val audioPreviewState by viewModel.audioPreviewState.collectAsStateWithLifecycle()
    val previewImageLoader = remember(context.applicationContext) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    var pendingDelete by remember { mutableStateOf<TaskRecord?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }
    var recoveryTask by remember { mutableStateOf<TaskRecord?>(null) }
    DisposableEffect(Unit) {
        viewModel.onTasksVisible()
        onDispose(viewModel::onTasksHidden)
    }
    DisposableEffect(previewImageLoader) {
        onDispose(previewImageLoader::shutdown)
    }
    if (tasks.isEmpty()) {
        EmptyTasksStatus()
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tasks, key = TaskRecord::id) { task ->
            val recoverable = task.fileState in setOf(
                FileState.PARTIAL,
                FileState.MISSING,
                FileState.STORAGE_UNAVAILABLE,
                FileState.DELETE_FAILED,
            )
            val containerColor = when (task.fileState) {
                FileState.PARTIAL -> MaterialTheme.colorScheme.tertiaryContainer
                FileState.MISSING -> MaterialTheme.colorScheme.surfaceVariant
                FileState.STORAGE_UNAVAILABLE, FileState.DELETE_FAILED ->
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
            val filesAvailable = task.outputUris.isNotEmpty() && task.fileState in setOf(
                FileState.AVAILABLE,
                FileState.PARTIAL,
                FileState.UNKNOWN,
            )
            val isPreviewExpanded = expandedTaskId == task.id
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (task.fileState == FileState.MISSING) 0.62f else 1f)
                    .then(
                        if (recoverable) Modifier.clickable { recoveryTask = task }
                        else Modifier,
                    ),
                colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { pendingDelete = task; deleteFiles = false },
                            enabled = task.status != TaskStatus.DELETING,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除任务")
                        }
                    }
                    Text(
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(task.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when (task.fileState) {
                            FileState.PARTIAL -> "部分文件已删除"
                            FileState.MISSING -> "文件已被删除"
                            FileState.STORAGE_UNAVAILABLE -> "保存目录已失效"
                            FileState.DELETE_FAILED -> "部分内容删除失败"
                            else -> task.stage
                        },
                    )
                    if (task.status == TaskStatus.COMPLETE) {
                        formatOutputSummary(task.outputs)?.let { summary ->
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (
                        task.status == TaskStatus.RUNNING &&
                        shouldShowDownloadProgress(task.stage)
                    ) {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (task.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                        OutlinedButton(onClick = { viewModel.cancelTask(task) }) { Text("取消") }
                    }
                    if (task.error.isNotBlank()) {
                        Text(task.error, color = MaterialTheme.colorScheme.error)
                    }
                    if (task.status == TaskStatus.FAILED && task.fileState != FileState.DELETE_FAILED) {
                        Button(onClick = { viewModel.retryTask(task) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("重试")
                        }
                    }
                    if (filesAvailable) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { viewModel.toggleTaskPreview(task.id) }) {
                                Icon(
                                    if (isPreviewExpanded) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(if (isPreviewExpanded) "收起" else "预览")
                            }
                            OutlinedButton(onClick = { viewModel.openTaskFolder(context, task) }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("文件夹")
                            }
                            Button(onClick = {
                                val files = resolveShareableFiles(
                                    context.contentResolver,
                                    task.outputUris,
                                )
                                if (files.isEmpty()) {
                                    viewModel.shareTaskFiles(context, task.id, emptyList())
                                } else {
                                    pendingShare = PendingShare(task.id, files)
                                }
                            }) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("分享")
                            }
                        }
                    }
                    if (isPreviewExpanded && filesAvailable) {
                        TaskPreviewPanel(
                            task = task,
                            audioState = audioPreviewState,
                            imageLoader = previewImageLoader,
                            onToggleAudio = { uri ->
                                viewModel.toggleAudioPreview(task.id, uri)
                            },
                            onSeekAudio = { positionMs ->
                                viewModel.seekAudioPreview(task.id, positionMs)
                            },
                        )
                    }
                }
            }
        }
    }
    pendingShare?.takeIf { it.files.isNotEmpty() }?.let { share ->
        ShareFilesSheet(
            files = share.files,
            onDismiss = { pendingShare = null },
            onShare = { selected ->
                pendingShare = null
                viewModel.shareTaskFiles(context, share.taskId, selected)
            },
        )
    }
    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null; deleteFiles = false },
            title = { Text("删除任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除“${task.title}”的任务记录吗？")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                        Text("同时删除下载内容和空任务文件夹")
                    }
                    Text(
                        "任务文件夹中的其他文件不会被删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (deleteFiles && viewModel.requiresAllFilesAccess(task)) {
                        requestAllFilesAccess()
                    } else {
                        viewModel.deleteTask(task, deleteFiles)
                        pendingDelete = null
                        deleteFiles = false
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null; deleteFiles = false }) {
                    Text("取消")
                }
            },
        )
    }
    recoveryTask?.let { task ->
        val storageUnavailable = task.fileState == FileState.STORAGE_UNAVAILABLE
        val deleteFailed = task.fileState == FileState.DELETE_FAILED
        val replacementStorageReady = storageUnavailable && viewModel.hasReplacementStorage(task)
        AlertDialog(
            onDismissRequest = { recoveryTask = null },
            title = {
                Text(
                    when {
                        storageUnavailable -> "保存目录已失效"
                        deleteFailed -> "部分内容删除失败"
                        task.fileState == FileState.PARTIAL -> "部分文件已删除"
                        else -> "文件已被删除"
                    },
                )
            },
            text = {
                Text(
                    when {
                        storageUnavailable && replacementStorageReady ->
                            "新的保存目录已经就绪，可以重新解析并完整下载。"
                        storageUnavailable -> "请重新选择保存目录，然后再次点击重新下载。"
                        deleteFailed -> "可以重试删除下载内容，或者只移除任务记录并保留残留文件。"
                        else -> "可以重新解析作品并完整下载，或者删除这条任务记录。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    when {
                        storageUnavailable && replacementStorageReady -> viewModel.retryTask(task)
                        storageUnavailable -> chooseFolder()
                        deleteFailed -> {
                            if (viewModel.requiresAllFilesAccess(task)) requestAllFilesAccess()
                            else viewModel.deleteTask(task, true)
                        }
                        viewModel.requiresAllFilesAccess(task) -> requestAllFilesAccess()
                        else -> viewModel.retryTask(task)
                    }
                    recoveryTask = null
                }) {
                    Text(
                        when {
                            storageUnavailable && replacementStorageReady -> "重新下载"
                            storageUnavailable -> "重新选择目录"
                            deleteFailed -> "重试删除"
                            else -> "重新下载"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (deleteFailed) {
                        viewModel.deleteTask(task, false)
                    } else {
                        pendingDelete = task
                        deleteFiles = false
                    }
                    recoveryTask = null
                }) { Text(if (deleteFailed) "仅删除任务记录" else "删除任务") }
            },
        )
    }
}

private sealed interface TaskPreviewLoadState {
    data object Loading : TaskPreviewLoadState
    data object Unavailable : TaskPreviewLoadState
    data class Ready(val media: TaskPreviewMedia) : TaskPreviewLoadState
}

@Composable
private fun TaskPreviewPanel(
    task: TaskRecord,
    audioState: AudioPreviewState,
    imageLoader: ImageLoader,
    onToggleAudio: (android.net.Uri) -> Unit,
    onSeekAudio: (Long) -> Unit,
) {
    val context = LocalContext.current
    var previewState by remember(task.id, task.outputs) {
        mutableStateOf<TaskPreviewLoadState>(TaskPreviewLoadState.Loading)
    }
    LaunchedEffect(task.id, task.outputs) {
        previewState = withContext(Dispatchers.IO) {
            resolveTaskPreview(context.contentResolver, task.outputs)
                ?.let(TaskPreviewLoadState::Ready)
                ?: TaskPreviewLoadState.Unavailable
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = previewState) {
            TaskPreviewLoadState.Loading -> PreviewLoading()
            TaskPreviewLoadState.Unavailable -> Text(
                "无法读取预览，文件可能已被移动或删除",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            is TaskPreviewLoadState.Ready -> when (state.media.kind) {
                TaskPreviewKind.IMAGE -> ImageOrVideoPreview(
                    media = state.media,
                    imageLoader = imageLoader,
                    isVideo = false,
                )
                TaskPreviewKind.VIDEO -> ImageOrVideoPreview(
                    media = state.media,
                    imageLoader = imageLoader,
                    isVideo = true,
                )
                TaskPreviewKind.AUDIO -> AudioPreviewControls(
                    taskId = task.id,
                    media = state.media,
                    state = audioState,
                    onToggleAudio = onToggleAudio,
                    onSeekAudio = onSeekAudio,
                )
            }
        }
    }
}

@Composable
private fun PreviewLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text("正在读取预览…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImageOrVideoPreview(
    media: TaskPreviewMedia,
    imageLoader: ImageLoader,
    isVideo: Boolean,
) {
    val context = LocalContext.current
    val model = remember(media.uri, isVideo) {
        ImageRequest.Builder(context)
            .data(media.uri)
            .apply { if (isVideo) videoFrameMillis(1_000) }
            .build()
    }
    Text(
        when {
            media.kind == TaskPreviewKind.IMAGE && media.matchingOutputCount > 1 ->
                "图片预览 · 共 ${media.matchingOutputCount} 张"
            isVideo -> "视频预览"
            else -> "图片预览"
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = model,
            imageLoader = imageLoader,
            contentDescription = if (isVideo) "视频缩略图" else "图片预览",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = { PreviewLoading() },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无法生成预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
        if (isVideo) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "视频文件",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun AudioPreviewControls(
    taskId: String,
    media: TaskPreviewMedia,
    state: AudioPreviewState,
    onToggleAudio: (android.net.Uri) -> Unit,
    onSeekAudio: (Long) -> Unit,
) {
    val isCurrent = state.taskId == taskId && state.uri == media.uri.toString()
    val status = if (isCurrent) state.status else AudioPreviewStatus.IDLE
    val durationMs = if (isCurrent) state.durationMs else 0L
    var sliderPosition by remember(taskId, media.uri) { mutableFloatStateOf(0f) }
    var dragging by remember(taskId, media.uri) { mutableStateOf(false) }
    LaunchedEffect(isCurrent, state.positionMs, dragging) {
        if (!dragging) sliderPosition = if (isCurrent) state.positionMs.toFloat() else 0f
    }

    Text(
        "音频预览",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onToggleAudio(media.uri) },
            enabled = status != AudioPreviewStatus.PREPARING,
        ) {
            if (status == AudioPreviewStatus.PREPARING) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (status == AudioPreviewStatus.PLAYING) Icons.Default.Pause
                    else Icons.Default.PlayArrow,
                    contentDescription = if (status == AudioPreviewStatus.PLAYING) "暂停" else "播放",
                )
            }
        }
        Slider(
            value = sliderPosition.coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
            onValueChange = {
                dragging = true
                sliderPosition = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeekAudio(sliderPosition.toLong())
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L && status !in setOf(
                AudioPreviewStatus.PREPARING,
                AudioPreviewStatus.ERROR,
            ),
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        "${formatPlaybackTime(sliderPosition.toLong())} / ${formatPlaybackTime(durationMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (isCurrent && status == AudioPreviewStatus.ERROR) {
        Text(
            "音频预览失败：${state.error}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(Locale.US, minutes, seconds)
}

private data class PendingShare(
    val taskId: String,
    val files: List<ShareableFile>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareFilesSheet(
    files: List<ShareableFile>,
    onDismiss: () -> Unit,
    onShare: (List<ShareableFile>) -> Unit,
) {
    var selectedUris by remember(files) { mutableStateOf(emptySet<String>()) }
    val selectedFiles = files.filter { it.uri.toString() in selectedUris }
    val selectedCategory = selectedFiles.firstOrNull()?.category
    val imageUris = files.filter { it.category == "image" }.map { it.uri.toString() }.toSet()

    fun toggle(file: ShareableFile) {
        val key = file.uri.toString()
        selectedUris = if (key in selectedUris) {
            selectedUris - key
        } else if (selectedCategory == null || selectedCategory == file.category) {
            selectedUris + key
        } else {
            selectedUris
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("选择要分享的文件", style = MaterialTheme.typography.titleLarge)
            Text(
                "可以多选同一类媒体；图片、视频和音频不能混合分享。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { selectedUris = imageUris },
                    enabled = imageUris.isNotEmpty(),
                ) { Text("全选图片") }
                TextButton(
                    onClick = { selectedUris = emptySet() },
                    enabled = selectedUris.isNotEmpty(),
                ) { Text("清空选择") }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(files, key = { it.uri.toString() }) { file ->
                    val checked = file.uri.toString() in selectedUris
                    val enabled = checked || selectedCategory == null || selectedCategory == file.category
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { toggle(file) },
                        headlineContent = {
                            Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                buildList {
                                    add(mediaCategoryLabel(file.category))
                                    if (file.sizeBytes > 0L) add(formatByteSize(file.sizeBytes))
                                    add(file.mimeType)
                                }.joinToString(" · "),
                            )
                        },
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle(file) },
                                enabled = enabled,
                            )
                        },
                    )
                }
            }
            Button(
                onClick = { onShare(selectedFiles) },
                enabled = selectedFiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (selectedFiles.isEmpty()) "选择文件" else "分享 ${selectedFiles.size} 个文件")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

internal fun mediaCategoryLabel(category: String): String = when (category) {
    "image" -> "图片"
    "video" -> "视频"
    "audio" -> "音频"
    else -> "文件"
}
