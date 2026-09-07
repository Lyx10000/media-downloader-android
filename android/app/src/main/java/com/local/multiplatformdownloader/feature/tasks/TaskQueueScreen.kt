package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.download.formatByteSize
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.feature.creator.CreatorProfile
import com.local.multiplatformdownloader.feature.creator.CreatorBatchSummary
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryUiState
import com.local.multiplatformdownloader.feature.creator.taskCreatorKey
import com.local.multiplatformdownloader.feature.home.MainUiState
import com.local.multiplatformdownloader.feature.home.MainViewModel
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

internal data class ActiveCreatorTaskGroup(
    val profile: CreatorProfile,
    val tasks: List<TaskRecord>,
    val currentBytesPerSecond: Long,
    val peakBytesPerSecond: Long,
    val progressFraction: Float,
    val batchSummary: CreatorBatchSummary? = null,
)

private enum class QueueKindFilter(val label: String) {
    ALL("全部"),
    INDEPENDENT("独立作品"),
    CREATOR("作者任务"),
}

private enum class QueueStatusFilter(val label: String) {
    ALL("全部状态"),
    RUNNING("下载中"),
    WAITING("等待/暂停"),
    FAILED("失败"),
}

internal fun isTaskQueueVisible(task: TaskRecord): Boolean = task.status !in setOf(
    TaskStatus.COMPLETE,
    TaskStatus.CANCELLED,
)

internal fun isLocalContentTask(task: TaskRecord): Boolean =
    task.outputs.isNotEmpty() || task.status == TaskStatus.COMPLETE

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun TaskQueueScreen(
    uiState: MainUiState,
    creatorState: CreatorLibraryUiState,
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
    onVisibleTaskIdsChanged: (Set<String>) -> Unit,
    onOpenCreator: (String) -> Unit,
) {
    val adaptive by viewModel.adaptiveDownloadState.collectAsStateWithLifecycle()
    val peakByAuthor by viewModel.authorTaskPeakBytesPerSecond.collectAsStateWithLifecycle()
    var kindFilter by rememberSaveable { mutableStateOf(QueueKindFilter.ALL) }
    var statusFilter by rememberSaveable { mutableStateOf(QueueStatusFilter.ALL) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val creatorByKey = creatorState.creators
        .filter { it.followed || it.archived }
        .associateBy(CreatorProfile::key)
    val platformTasks = uiState.allTasks.asSequence()
        .filter(::isTaskQueueVisible)
        .filter { platformFilter == null || it.platform == platformFilter }
        .filter { task ->
            when (statusFilter) {
                QueueStatusFilter.ALL -> true
                QueueStatusFilter.RUNNING -> task.status == TaskStatus.RUNNING
                QueueStatusFilter.WAITING -> task.status in setOf(TaskStatus.QUEUED, TaskStatus.PAUSED)
                QueueStatusFilter.FAILED -> task.status == TaskStatus.FAILED
            }
        }
        .toList()
    val groupedTasks = platformTasks.filter { taskCreatorKey(it) in creatorByKey }
        .groupBy(::taskCreatorKey)
    val creatorGroups = if (kindFilter == QueueKindFilter.INDEPENDENT) emptyList() else {
        val visibleBatchKeys = if (statusFilter in setOf(QueueStatusFilter.ALL, QueueStatusFilter.WAITING)) {
            creatorState.batchSummaries.filterValues(CreatorBatchSummary::visible).keys
        } else {
            emptySet()
        }
        (groupedTasks.keys + visibleBatchKeys).mapNotNull { creatorKey ->
            val tasks = groupedTasks[creatorKey].orEmpty()
            creatorByKey[creatorKey]
                ?.takeIf { platformFilter == null || it.platform == platformFilter }
                ?.let { profile ->
                ActiveCreatorTaskGroup(
                    profile = profile,
                    tasks = tasks.sortedByDescending(TaskRecord::createdAt),
                    currentBytesPerSecond = tasks.sumOf { adaptive.taskBytesPerSecond[it.id] ?: 0L },
                    peakBytesPerSecond = peakByAuthor[creatorKey] ?: 0L,
                    progressFraction = tasks.map { task ->
                        adaptive.taskProgressFraction[task.id] ?: task.progress.coerceIn(0, 100) / 100f
                    }.average().takeUnless(Double::isNaN)?.toFloat() ?: 0f,
                    batchSummary = creatorState.batchSummaries[creatorKey]
                        ?.takeIf(CreatorBatchSummary::visible),
                )
            }
        }.sortedByDescending { group -> group.tasks.maxOfOrNull(TaskRecord::createdAt) ?: 0L }
    }
    val independentTasks = if (kindFilter == QueueKindFilter.CREATOR) emptyList() else {
        platformTasks.filterNot { taskCreatorKey(it) in creatorByKey }
    }
    val displayedTaskIds = (independentTasks.asSequence() + creatorGroups.asSequence()
        .flatMap { it.tasks.asSequence() })
        .filter { it.status != TaskStatus.DELETING }
        .map(TaskRecord::id)
        .toCollection(linkedSetOf())
    LaunchedEffect(displayedTaskIds) { onVisibleTaskIdsChanged(displayedTaskIds) }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AdaptiveConcurrencyStatusCard(adaptive)
        }
        OutlinedCard(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clickable { showFilters = true },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("筛选", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${kindFilter.label} · ${platformFilter?.displayName ?: "全部平台"} · " +
                            statusFilter.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TasksScreen(
            tasks = independentTasks,
            questionArchives = uiState.questionArchives,
            viewModel = viewModel,
            chooseFolder = chooseFolder,
            requestAllFilesAccess = requestAllFilesAccess,
            onManageTask = onManageTask,
            selectionMode = selectionMode,
            selectedTaskIds = selectedTaskIds,
            platformFilter = null,
            onPlatformFilter = {},
            onToggleTaskSelection = onToggleTaskSelection,
            focusedTaskId = focusedTaskId,
            onTaskFocused = onTaskFocused,
            onOpenQuestionArchive = onOpenQuestionArchive,
            modifier = Modifier.weight(1f),
            showPlatformFilter = false,
            showAdaptiveStatus = false,
            allowPreview = false,
            allowFileActions = false,
            emptyText = "暂无进行中的任务",
            creatorGroups = creatorGroups,
            onOpenCreatorGroup = onOpenCreator,
        )
    }
    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("筛选任务", style = MaterialTheme.typography.titleLarge)
                Text("任务类型", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QueueKindFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = kindFilter == filter,
                            onClick = { kindFilter = filter },
                            label = { Text(filter.label) },
                        )
                    }
                }
                HorizontalDivider()
                Text("平台", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = platformFilter == null,
                        onClick = { onPlatformFilter(null) },
                        label = { Text("全部平台") },
                    )
                    SourcePlatform.entries.forEach { platform ->
                        FilterChip(
                            selected = platformFilter == platform,
                            onClick = { onPlatformFilter(platform) },
                            label = { Text(platform.displayName) },
                        )
                    }
                }
                HorizontalDivider()
                Text("任务状态", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QueueStatusFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = statusFilter == filter,
                            onClick = { statusFilter = filter },
                            label = { Text(filter.label) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        kindFilter = QueueKindFilter.ALL
                        statusFilter = QueueStatusFilter.ALL
                        onPlatformFilter(null)
                    }) { Text("重置") }
                    Button(onClick = { showFilters = false }) { Text("完成") }
                }
            }
        }
    }
}

@Composable
internal fun ActiveCreatorTaskGroupCard(
    group: ActiveCreatorTaskGroup,
    platformRiskUntil: Map<SourcePlatform, Long>,
    onOpen: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggle: () -> Unit = {},
) {
    val progress = group.batchSummary?.progressFraction?.takeIf { group.tasks.isEmpty() }
        ?: group.progressFraction.coerceIn(0f, 1f)
    val workCount = maxOf(group.tasks.size, group.batchSummary?.selected ?: 0)
    val waitingForRisk = (platformRiskUntil[group.profile.platform] ?: 0L) >
        System.currentTimeMillis() && group.tasks.any { it.status == TaskStatus.QUEUED }
    OutlinedCard(
        Modifier.fillMaxWidth().clickable {
            if (selectionMode && group.tasks.isNotEmpty()) onToggle() else onOpen()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode && group.tasks.isNotEmpty()) {
                    Checkbox(checked = selected, onCheckedChange = { onToggle() })
                }
                AsyncImage(
                    model = group.profile.avatarUrl,
                    contentDescription = "${group.profile.nickname}头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(MaterialTheme.shapes.extraLarge),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        group.profile.nickname,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlatformBrandBadge(group.profile.platform)
                        Text("$workCount 个活动作品", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                if (waitingForRisk) "等待风控冷却"
                else if (group.batchSummary?.paused?.let { it > 0 } == true) "批量准备已暂停，点击继续"
                else if (group.batchSummary?.foregroundRequired?.let { it > 0 } == true) "需要前台准备，点击继续"
                else {
                    "总速度 ${formatByteSize(group.currentBytesPerSecond)}/s · " +
                        "峰值 ${formatByteSize(group.peakBytesPerSecond)}/s"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (waitingForRisk) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("点击查看各作品进度", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ActiveCreatorTaskGroupDialog(
    group: ActiveCreatorTaskGroup,
    viewModel: MainViewModel,
    requestAllFilesAccess: () -> Unit,
    onManageTask: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteFiles by remember { mutableStateOf(false) }
    val pausable = group.tasks.filter { it.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING) }
    val resumable = group.tasks.filter { it.status == TaskStatus.PAUSED }
    val failed = group.tasks.filter { it.status == TaskStatus.FAILED }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回任务列表")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(group.profile.nickname, style = MaterialTheme.typography.titleMedium)
                        Text("${group.tasks.size} 个活动作品", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(
                        enabled = pausable.isNotEmpty(),
                        onClick = { pausable.forEach(viewModel::pauseTask) },
                    ) { Icon(Icons.Default.Pause, contentDescription = "暂停全部") }
                    IconButton(
                        enabled = resumable.isNotEmpty(),
                        onClick = { resumable.forEach(viewModel::resumeTask) },
                    ) { Icon(Icons.Default.PlayArrow, contentDescription = "继续全部") }
                    IconButton(
                        enabled = failed.isNotEmpty(),
                        onClick = { viewModel.retryTasks(failed) },
                    ) { Icon(Icons.Default.Refresh, contentDescription = "重试失败项") }
                    IconButton(
                        enabled = group.tasks.isNotEmpty(),
                        onClick = { confirmCancel = true },
                    ) { Icon(Icons.Default.Cancel, contentDescription = "取消全部") }
                    IconButton(
                        enabled = group.tasks.isNotEmpty(),
                        onClick = { confirmDelete = true },
                    ) { Icon(Icons.Default.Delete, contentDescription = "删除全部") }
                }
                HorizontalDivider()
                TasksScreen(
                    tasks = group.tasks,
                    questionArchives = emptyMap(),
                    viewModel = viewModel,
                    chooseFolder = {},
                    requestAllFilesAccess = requestAllFilesAccess,
                    onManageTask = onManageTask,
                    selectionMode = false,
                    selectedTaskIds = emptySet(),
                    platformFilter = null,
                    onPlatformFilter = {},
                    onToggleTaskSelection = {},
                    focusedTaskId = null,
                    onTaskFocused = {},
                    onOpenQuestionArchive = {},
                    groupBilibili = false,
                    modifier = Modifier.weight(1f),
                    showPlatformFilter = false,
                    showAdaptiveStatus = false,
                    allowPreview = false,
                    allowFileActions = false,
                )
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("取消该作者的活动任务？") },
            text = { Text("正在运行和等待中的作品会停止，已经下载完成的文件不会删除。") },
            confirmButton = {
                Button(onClick = {
                    group.tasks.filter { it.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.PAUSED) }
                        .forEach(viewModel::cancelTask)
                    confirmCancel = false
                }) { Text("取消任务") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("返回") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false; deleteFiles = false },
            title = { Text("删除该作者的活动任务？") },
            text = {
                Column {
                    Text("将删除当前卡片内的 ${group.tasks.size} 个任务记录。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                        Text("同时删除已产生的本地文件")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (deleteFiles && group.tasks.any(viewModel::requiresAllFilesAccess)) {
                        requestAllFilesAccess()
                    } else {
                        viewModel.deleteTasks(group.tasks, deleteFiles)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalContentDetailScreen(
    task: TaskRecord,
    questionArchives: Map<String, com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchive>,
    viewModel: MainViewModel,
    requestAllFilesAccess: () -> Unit,
    onManageTask: (String) -> Unit,
    onOpenQuestionArchive: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回本地下载")
                }
            },
        )
        HorizontalDivider()
        TasksScreen(
            tasks = listOf(task),
            questionArchives = questionArchives,
            viewModel = viewModel,
            chooseFolder = {},
            requestAllFilesAccess = requestAllFilesAccess,
            onManageTask = onManageTask,
            selectionMode = false,
            selectedTaskIds = emptySet(),
            platformFilter = null,
            onPlatformFilter = {},
            onToggleTaskSelection = {},
            focusedTaskId = task.id,
            onTaskFocused = {},
            onOpenQuestionArchive = onOpenQuestionArchive,
            groupBilibili = false,
            modifier = Modifier.weight(1f),
            showPlatformFilter = false,
            showAdaptiveStatus = false,
            allowPreview = true,
            showTransferControls = false,
            emptyText = "本地内容不存在",
        )
    }
}
