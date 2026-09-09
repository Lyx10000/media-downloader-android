package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.download.formatByteSize
import com.local.multiplatformdownloader.core.download.formatOutputSummary
import com.local.multiplatformdownloader.core.download.shouldShowDownloadProgress
import com.local.multiplatformdownloader.core.model.FileState
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.feature.home.MainViewModel
import com.local.multiplatformdownloader.feature.preview.MediaPreviewState
import com.local.multiplatformdownloader.feature.preview.MediaPreviewStatus
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchive
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionStatus
import com.local.multiplatformdownloader.feature.zhihuarchive.canContinueQuestionArchive
import com.local.multiplatformdownloader.platform.bilibili.BilibiliTaskGroupCard
import com.local.multiplatformdownloader.platform.bilibili.BilibiliTaskGroupDialog
import com.local.multiplatformdownloader.platform.bilibili.bilibiliGroupKey
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
internal fun EmptyTasksStatus(text: String = "暂无进行中的任务") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TasksScreen(
    tasks: List<TaskRecord>,
    questionArchives: Map<String, ZhihuQuestionArchive>,
    viewModel: MainViewModel,
    chooseFolder: () -> Unit,
    requestAllFilesAccess: () -> Unit,
    onManageTask: (String) -> Unit,
    selectionMode: Boolean,
    selectedTaskIds: Set<String>,
    platformFilter: SourcePlatform?,
    onPlatformFilter: (SourcePlatform?) -> Unit,
    onToggleTaskSelection: (String) -> Unit,
    focusedTaskId: String?,
    onTaskFocused: () -> Unit,
    onOpenQuestionArchive: (String) -> Unit,
    groupBilibili: Boolean = true,
    modifier: Modifier = Modifier,
    showPlatformFilter: Boolean = groupBilibili,
    showAdaptiveStatus: Boolean = groupBilibili,
    allowPreview: Boolean = true,
    allowFileActions: Boolean = true,
    showTransferControls: Boolean = true,
    showPublishedAt: Boolean = false,
    emptyText: String = "暂无进行中的任务",
    creatorGroups: List<ActiveCreatorTaskGroup> = emptyList(),
    onOpenCreatorGroup: (String) -> Unit = {},
    onResumeCreatorBatch: (String) -> Unit = {},
    onDeleteCreatorBatch: (String) -> Unit = {},
    onForceCreatorBatchWork: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val expandedTaskId by viewModel.expandedTaskId.collectAsStateWithLifecycle()
    val mediaPreviewState by viewModel.mediaPreviewState.collectAsStateWithLifecycle()
    val fullscreenTaskId by viewModel.fullscreenTaskId.collectAsStateWithLifecycle()
    val trackDownloadProgress by viewModel.trackDownloadProgress.collectAsStateWithLifecycle()
    val adaptiveDownloadState by viewModel.adaptiveDownloadState.collectAsStateWithLifecycle()
    val previewImageLoader = remember(context.applicationContext) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    var pendingDelete by remember { mutableStateOf<TaskRecord?>(null) }
    var pendingRedownload by remember { mutableStateOf<TaskRecord?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }
    var recoveryTask by remember { mutableStateOf<TaskRecord?>(null) }
    DisposableEffect(previewImageLoader) {
        onDispose(previewImageLoader::shutdown)
    }
    if (tasks.isEmpty() && creatorGroups.isEmpty()) {
        EmptyTasksStatus(emptyText)
        return
    }
    var openBilibiliGroup by remember { mutableStateOf<String?>(null) }
    var openCreatorGroup by remember { mutableStateOf<String?>(null) }
    val bilibiliGroups = if (groupBilibili) tasks.filter { bilibiliGroupKey(it) != null }.groupBy { bilibiliGroupKey(it)!! } else emptyMap()
    val ungroupedTasks = tasks.filter { task -> bilibiliGroupKey(task)?.let { bilibiliGroups[it]?.firstOrNull()?.id?.let { id -> id == task.id } } ?: true }
    val visibleTasks = if (platformFilter == null) ungroupedTasks else {
        ungroupedTasks.filter { it.platform == platformFilter }
    }
    val listState = rememberLazyListState()
    val focusedTaskIndex = focusedTaskId?.let { taskId ->
        val group = tasks.firstOrNull { it.id == taskId }?.let(::bilibiliGroupKey)
        visibleTasks.indexOfFirst { it.id == taskId || group != null && bilibiliGroupKey(it) == group }.takeIf { it >= 0 }
    }
    LaunchedEffect(focusedTaskId, focusedTaskIndex) {
        if (focusedTaskIndex != null) {
            // The platform filter occupies the first list item.
            val headerCount = if (groupBilibili) {
                1 + if (adaptiveDownloadState.shouldDisplay()) 1 else 0
            } else 0
            listState.scrollToItem(focusedTaskIndex + headerCount)
            onTaskFocused()
        }
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPlatformFilter) item(key = "platform-filter") {
            PlatformFilterBar(selected = platformFilter, onSelected = onPlatformFilter)
        }
        if (showAdaptiveStatus && adaptiveDownloadState.shouldDisplay()) {
            item(key = "adaptive-concurrency") {
                AdaptiveConcurrencyStatusCard(adaptiveDownloadState)
            }
        }
        if (creatorGroups.isEmpty() && visibleTasks.isEmpty()) {
            item(key = "empty-platform") {
                Text(
                    "当前平台还没有下载任务",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(creatorGroups, key = { "creator-group:${it.profile.key}" }) { group ->
            ActiveCreatorTaskGroupCard(
                group = group,
                platformRiskUntil = adaptiveDownloadState.platformRiskUntil,
                onOpen = {
                    if (group.tasks.isEmpty() && group.batchSummary == null) onOpenCreatorGroup(group.profile.key)
                    else openCreatorGroup = group.profile.key
                },
                selectionMode = selectionMode,
                selected = group.tasks.all { it.id in selectedTaskIds },
                onToggle = {
                    val remove = group.tasks.all { it.id in selectedTaskIds }
                    group.tasks
                        .filter { it.status != TaskStatus.DELETING && ((it.id in selectedTaskIds) == remove) }
                        .forEach { onToggleTaskSelection(it.id) }
                },
            )
        }
        items(visibleTasks, key = TaskRecord::id) { task ->
            val group = bilibiliGroupKey(task)?.let { bilibiliGroups[it] }
            if (group != null) {
                BilibiliTaskGroupCard(
                    group,
                    group.all { it.id in selectedTaskIds },
                    selectionMode,
                    trackDownloadProgress,
                    onOpen = { openBilibiliGroup = bilibiliGroupKey(task) },
                    onToggle = {
                        val remove = group.all { it.id in selectedTaskIds }
                        group.filter { it.status != TaskStatus.DELETING && ((it.id in selectedTaskIds) == remove) }
                            .forEach { onToggleTaskSelection(it.id) }
                    },
                    showPublishedAt = showPublishedAt,
                )
                return@items
            }
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
            val isQuestionArchive = task.questionArchiveId.isNotBlank() && !task.questionChild
            val questionArchive = questionArchives[task.id]
            val canContinueQuestion = questionArchive?.let { archive ->
                archive.status !in setOf(
                    ZhihuQuestionStatus.QUEUED,
                    ZhihuQuestionStatus.RUNNING,
                ) && canContinueQuestionArchive(
                    archive,
                    archive.nextOffset.coerceAtLeast(0),
                )
            } == true
            val canResumeQuestion = isQuestionArchive && task.status in setOf(
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
            )
            val canRedownload = !isQuestionArchive && isTaskRedownloadEligible(task)
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (task.fileState == FileState.MISSING) 0.62f else 1f)
                    .then(
                        if (selectionMode) {
                            if (task.status != TaskStatus.DELETING) {
                                Modifier.clickable { onToggleTaskSelection(task.id) }
                            } else {
                                Modifier
                            }
                        } else if (recoverable) Modifier.clickable { recoveryTask = task }
                        else Modifier,
                    ),
                colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = task.id in selectedTaskIds,
                                onCheckedChange = { onToggleTaskSelection(task.id) },
                                enabled = task.status != TaskStatus.DELETING,
                            )
                        }
                        if (task.coverUrl.isNotBlank()) {
                            SubcomposeAsyncImage(
                                model = task.coverUrl,
                                imageLoader = previewImageLoader,
                                contentDescription = "${task.title}缩略图",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(58.dp)
                                    .clip(MaterialTheme.shapes.medium),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            when (task.platform) {
                                SourcePlatform.BILIBILI -> task.authorAccountId
                                    .takeIf(String::isNotBlank)
                                    ?.let { uid ->
                                        CreatorIdentityLine(
                                            label = "B站 UID", value = uid,
                                            onCopy = {
                                                copyCreatorText(context, "B站 UID", uid)
                                                viewModel.showMessage("B站 UID 已复制")
                                            },
                                        )
                                    }
                                SourcePlatform.DOUYIN -> task.authorAccountId
                                    .takeIf(String::isNotBlank)
                                    ?.let { accountId ->
                                        CreatorIdentityLine(
                                            label = "抖音号",
                                            value = accountId,
                                            onCopy = {
                                                copyCreatorText(context, "抖音号", accountId)
                                                viewModel.showMessage("抖音号已复制")
                                            },
                                        )
                                    }
                                SourcePlatform.XIAOHONGSHU -> task.authorAccountId
                                    .takeIf(String::isNotBlank)
                                    ?.let { accountId ->
                                        CreatorIdentityLine(
                                            label = "小红书号",
                                            value = accountId,
                                            onCopy = {
                                                copyCreatorText(context, "小红书号", accountId)
                                                viewModel.showMessage("小红书号已复制")
                                            },
                                        )
                                    }
                                SourcePlatform.ZHIHU -> task.author
                                    .takeIf(String::isNotBlank)
                                    ?.let { author ->
                                        CreatorIdentityLine(
                                            label = "作者",
                                            value = author,
                                            copyDescription = "复制知乎昵称",
                                            onCopy = {
                                                copyCreatorText(context, "知乎昵称", author)
                                                viewModel.showMessage("知乎昵称已复制")
                                            },
                                        )
                                    }
                                SourcePlatform.X, SourcePlatform.INSTAGRAM -> task.authorAccountId
                                    .takeIf(String::isNotBlank)
                                    ?.let { accountId ->
                                        CreatorIdentityLine(
                                            label = "${task.platform.displayName} 用户名",
                                            value = "@$accountId",
                                            copyDescription = "复制 ${task.platform.displayName} 用户名",
                                            onCopy = {
                                                copyCreatorText(context, "${task.platform.displayName} 用户名", "@$accountId")
                                                viewModel.showMessage("${task.platform.displayName} 用户名已复制")
                                            },
                                        )
                                    }
                            }
                        }
                        if (!selectionMode) {
                            IconButton(
                                onClick = { pendingDelete = task; deleteFiles = false },
                                enabled = task.status != TaskStatus.DELETING,
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除任务")
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PlatformBrandBadge(task.platform)
                        Text(
                            if (showPublishedAt) {
                                task.publishedAt.takeIf { it > 0L }?.let { publishedAt ->
                                    "发布时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                                        .format(Date(publishedAt))
                                } ?: "发布时间未知"
                            } else {
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                                    .format(Date(task.createdAt))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        when {
                            task.status == TaskStatus.QUEUED &&
                                (adaptiveDownloadState.platformRiskUntil[task.platform] ?: 0L) >
                                System.currentTimeMillis() -> "等待风控冷却"
                            else -> when (task.fileState) {
                                FileState.PARTIAL -> "部分文件已删除"
                                FileState.MISSING -> "文件已被删除"
                                FileState.STORAGE_UNAVAILABLE -> "保存目录已失效"
                                FileState.DELETE_FAILED -> "部分内容删除失败"
                                else -> task.stage
                            }
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
                    val taskTrackProgress = trackDownloadProgress[task.id]
                    if (showTransferControls && taskTrackProgress != null && task.status == TaskStatus.RUNNING) {
                        TrackDownloadProgressBars(taskTrackProgress)
                    } else if (
                        showTransferControls &&
                        task.status == TaskStatus.RUNNING &&
                        shouldShowDownloadProgress(task.stage)
                    ) {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (showTransferControls && !selectionMode &&
                        task.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.pauseTask(task) }) { Text("暂停") }
                            OutlinedButton(onClick = { viewModel.cancelTask(task) }) { Text("取消") }
                        }
                    } else if (showTransferControls && !selectionMode && task.status == TaskStatus.PAUSED) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.resumeTask(task) }) { Text("继续") }
                            OutlinedButton(onClick = { viewModel.cancelTask(task) }) { Text("取消") }
                        }
                    }
                    if (task.error.isNotBlank()) {
                        Text(task.error, color = MaterialTheme.colorScheme.error)
                    }
                    if (!selectionMode && (
                            filesAvailable || canRedownload || isQuestionArchive ||
                                canContinueQuestion || canResumeQuestion
                            )
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isQuestionArchive) {
                                OutlinedButton(onClick = { onOpenQuestionArchive(task.id) }) {
                                    Text("查看回答")
                                    Spacer(Modifier.size(4.dp))
                                    Icon(Icons.Default.ChevronRight, contentDescription = null)
                                }
                            }
                            if (canContinueQuestion || canResumeQuestion) {
                                OutlinedButton(onClick = {
                                    if (canContinueQuestion) {
                                        viewModel.continueQuestionArchive(task)
                                    } else {
                                        viewModel.retryTask(task)
                                    }
                                }) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(if (canContinueQuestion) "继续下载" else "继续归档")
                                }
                            }
                            if (canRedownload) {
                                OutlinedButton(onClick = {
                                    if (task.fileState == FileState.STORAGE_UNAVAILABLE) {
                                        recoveryTask = task
                                    } else {
                                        pendingRedownload = task
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("重新下载")
                                }
                            }
                            if (filesAvailable && allowFileActions) {
                                if (allowPreview) {
                                OutlinedButton(onClick = { viewModel.toggleTaskPreview(task.id) }) {
                                    Icon(
                                        if (isPreviewExpanded) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = null,
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(if (isPreviewExpanded) "收起" else "预览")
                                }
                                }
                                OutlinedButton(onClick = { onManageTask(task.id) }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("管理文件")
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
                    }
                    if (allowPreview && !selectionMode && isPreviewExpanded && filesAvailable) {
                        TaskPreviewPanel(
                            task = task,
                            mediaState = mediaPreviewState,
                            imageLoader = previewImageLoader,
                            isFullscreen = fullscreenTaskId == task.id,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
    if (allowPreview && openBilibiliGroup == null && openCreatorGroup == null &&
        fullscreenTaskId != null && mediaPreviewState.taskId == fullscreenTaskId
    ) {
        val source = mediaPreviewState.source
        if (source?.kind == TaskPreviewKind.VIDEO && mediaPreviewState.player != null) {
            FullscreenVideoPreview(
                state = mediaPreviewState,
                onToggle = { viewModel.toggleMediaPreview(fullscreenTaskId!!, source) },
                onSeek = { viewModel.seekMediaPreview(fullscreenTaskId!!, it) },
                onToggleMute = { viewModel.toggleMediaMute(fullscreenTaskId!!) },
                onDismiss = viewModel::exitFullscreen,
            )
        }
    }
    openCreatorGroup?.let { creatorKey ->
        val group = creatorGroups.firstOrNull { it.profile.key == creatorKey }
        if (group != null) {
            ActiveCreatorTaskGroupDialog(
                group = group,
                viewModel = viewModel,
                requestAllFilesAccess = requestAllFilesAccess,
                onManageTask = onManageTask,
                onResumeCreatorBatch = onResumeCreatorBatch,
                onDeleteCreatorBatch = onDeleteCreatorBatch,
                onForceCreatorBatchWork = onForceCreatorBatchWork,
                onDismiss = { openCreatorGroup = null },
            )
        } else {
            LaunchedEffect(creatorKey) { openCreatorGroup = null }
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
    openBilibiliGroup?.let { bv ->
        BilibiliTaskGroupDialog(
            bv,
            null,
            viewModel,
            requestAllFilesAccess,
            onManageTask,
            { openBilibiliGroup = null },
            showPublishedAt = showPublishedAt,
        )
    }
    pendingRedownload?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingRedownload = null },
            title = { Text("重新下载") },
            text = {
                Text(
                    "将重新解析作品，删除该任务已登记的现有下载文件，并创建新的任务文件夹。" +
                        "任务文件夹中的其他文件会保留。",
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (viewModel.requiresAllFilesAccess(task)) {
                        requestAllFilesAccess()
                        viewModel.showMessage("授权后请再次点击重新下载")
                    } else {
                        viewModel.retryTask(task)
                    }
                    pendingRedownload = null
                }) { Text("重新下载") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRedownload = null }) { Text("取消") }
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

internal fun toggleTaskSelection(selected: Set<String>, taskId: String): Set<String> =
    if (taskId in selected) selected - taskId else selected + taskId

internal fun reconcileTaskSelection(selected: Set<String>, available: Set<String>): Set<String> =
    selected.intersect(available)

internal fun isTaskRedownloadEligible(task: TaskRecord): Boolean =
    task.status !in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.DELETING) &&
        task.fileState != FileState.DELETE_FAILED

internal fun batchRedownloadSummary(started: Int, failed: Int, skipped: Int): String = buildList {
    if (started > 0) add("已开始 $started 个任务")
    if (failed > 0) add("$failed 个启动失败")
    if (skipped > 0) add("$skipped 个状态不允许重新下载")
}.joinToString("，").ifBlank { "没有可重新下载的任务" }
