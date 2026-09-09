package com.local.multiplatformdownloader.feature.creator

import com.local.multiplatformdownloader.core.download.shouldShowDownloadProgress
import com.local.multiplatformdownloader.core.download.TaskTrackDownloadProgress
import com.local.multiplatformdownloader.core.download.formatByteSize
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.settings.BatchDownloadSettings
import com.local.multiplatformdownloader.core.settings.BatchVideoQuality
import com.local.multiplatformdownloader.feature.home.CREATOR_BATCH_SNAPSHOT_DELAY_MS
import com.local.multiplatformdownloader.feature.home.MainUiState
import com.local.multiplatformdownloader.feature.home.MainViewModel
import com.local.multiplatformdownloader.feature.home.WebEnvironment
import com.local.multiplatformdownloader.feature.tasks.TasksScreen
import com.local.multiplatformdownloader.feature.tasks.PlatformFilterBar
import com.local.multiplatformdownloader.feature.tasks.TrackDownloadProgressBars
import com.local.multiplatformdownloader.feature.tasks.isTaskRedownloadEligible
import com.local.multiplatformdownloader.feature.tasks.reconcileTaskSelection
import com.local.multiplatformdownloader.feature.tasks.toggleTaskSelection
import com.local.multiplatformdownloader.platform.bilibili.BilibiliTaskGroupCard
import com.local.multiplatformdownloader.platform.bilibili.BilibiliTaskGroupDialog
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
internal fun CreatorCard(
    profile: CreatorProfile,
    taskSummary: CreatorTaskSummary?,
    onClick: () -> Unit,
    archived: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onSelectedChange: () -> Unit = {},
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().alpha(if (archived) 0.68f else 1f).clickable(onClick = onClick),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelectedChange() },
                )
            }
            AsyncImage(
                model = profile.avatarUrl,
                contentDescription = "${profile.nickname}头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(60.dp).clip(MaterialTheme.shapes.extraLarge),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBrandBadge(profile.platform)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        profile.nickname,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                profile.accountId.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    creatorStatusLabel(profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                taskSummary?.takeIf { it.localCount > 0 }?.let { summary ->
                    Text(
                        "本地 ${summary.localCount} 个作品" +
                            summary.downloadedBytes.takeIf { it > 0L }
                                ?.let { " · ${formatByteSize(it)}" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!selectionMode) {
                Icon(Icons.Default.ChevronRight, contentDescription = "打开作者")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreatorDetailScreen(
    state: CreatorLibraryUiState,
    viewModel: CreatorLibraryViewModel,
    mainViewModel: MainViewModel,
    onManageTask: (String) -> Unit,
    onOpenLocalTask: (String) -> Unit,
    requestAllFilesAccess: () -> Unit,
) {
    val profile = state.selectedCreator ?: return
    var detailTab by remember(profile.key) { mutableIntStateOf(viewModel.detailTab(profile.key)) }
    var showBatchSettings by remember { mutableStateOf(false) }
    var openBilibiliGroup by remember(profile.key) { mutableStateOf<String?>(null) }
    var showStopDialog by remember { mutableStateOf(false) }
    var recordSelectionMode by remember(profile.key) { mutableStateOf(false) }
    var selectedLocalWorkKeys by remember(profile.key) { mutableStateOf<Set<String>>(emptySet()) }
    var showRecordDeleteDialog by remember(profile.key) { mutableStateOf(false) }
    var showRecordRedownloadDialog by remember(profile.key) { mutableStateOf(false) }
    var deleteRecordFiles by remember(profile.key) { mutableStateOf(false) }
    var showLocalSortMenu by remember { mutableStateOf(false) }
    val displayedWorks = if (detailTab == 0) state.pageWorks else {
        sortCreatorLocalWorks(state.allWorks.filter(CreatorWork::hasLocalContent), state.localSort)
    }
    val selectableLocalWorkKeys = state.allWorks
        .filter(CreatorWork::hasLocalContent)
        .filter { it.task?.status != TaskStatus.DELETING }
        .mapTo(linkedSetOf(), CreatorWork::key)
    val selectedLocalWorks = state.allWorks.filter { it.key in selectedLocalWorkKeys }
    val selectedPreparations = selectedLocalWorks.filter { it.preparationActionable }.mapTo(linkedSetOf(), CreatorWork::key)
    val selectedRecordTasks = selectedLocalWorks.flatMap { it.relatedTasks.ifEmpty { listOfNotNull(it.task) } }.distinctBy(TaskRecord::id)
    val selectedRecordRedownloadTasks = selectedRecordTasks.filter(::isTaskRedownloadEligible)
    val retryCount = selectedRecordRedownloadTasks.size + selectedPreparations.size
    val publicPosition = remember(profile.key) { viewModel.detailListPosition(profile.key, 0) }
    val recordPosition = remember(profile.key) { viewModel.detailListPosition(profile.key, 1) }
    val publicListState = rememberLazyListState(publicPosition.index, publicPosition.offset)
    val recordListState = rememberLazyListState(recordPosition.index, recordPosition.offset)
    val listState = if (detailTab == 0) publicListState else recordListState
    val saveCurrentListPosition = {
        viewModel.saveDetailTab(profile.key, detailTab)
        viewModel.saveDetailListPosition(
            profile.key,
            detailTab,
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
        )
    }
    val preparation = state.batchPreparations[profile.key]
    val localDownloadBytes = state.taskSummaries[profile.key]?.downloadedBytes ?: 0L
    val lifecycleOwner = LocalLifecycleOwner.current
    BackHandler {
        if (recordSelectionMode) {
            recordSelectionMode = false
            selectedLocalWorkKeys = emptySet()
        } else {
            viewModel.closeCreator()
        }
    }
    LaunchedEffect(detailTab, selectableLocalWorkKeys) {
        selectedLocalWorkKeys = reconcileTaskSelection(
            selectedLocalWorkKeys,
            selectableLocalWorkKeys,
        )
        if (detailTab != 1 || selectableLocalWorkKeys.isEmpty()) {
            recordSelectionMode = false
            selectedLocalWorkKeys = emptySet()
        }
    }
    DisposableEffect(profile.key, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.pauseBatchPreparation(showMessage = false)
                viewModel.cancelPageWebRefresh(showMessage = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseBatchPreparation(showMessage = false)
            viewModel.cancelPageWebRefresh(showMessage = false)
        }
    }

    state.pageWebRefreshRequest?.takeIf { it.creatorKey == profile.key }?.let { request ->
        key(request.requestId) {
            WebEnvironment(
                platform = profile.platform,
                url = request.url,
                title = "正在刷新${profile.platform.displayName}作者作品",
                capturePage = true,
                snapshotDelayMs = CREATOR_BATCH_SNAPSHOT_DELAY_MS,
                statusText = "正在读取作者主页；页面不会显示，原有作品缓存会保留。",
                cancelLabel = "取消刷新",
                onReady = viewModel::completePageWebRefresh,
                onCancel = viewModel::cancelPageWebRefresh,
            )
        }
        return
    }

    if (preparation?.needsForeground == true) {
        val request = preparation.webRequest
        if (request != null) {
            key(request.batchId, request.workKey, request.attemptCount) {
                WebEnvironment(
                    platform = profile.platform,
                    url = request.url,
                    title = "正在准备${profile.platform.displayName}作品 ${request.position}/${request.total}",
                    capturePage = true,
                    snapshotDelayMs = CREATOR_BATCH_SNAPSHOT_DELAY_MS,
                    statusText = "请保持应用在前台。切到后台、锁屏或离开作者页面会暂停。",
                    cancelLabel = "暂停准备",
                    onReady = { cookie, source, snapshot ->
                        viewModel.completeBatchWebPreparation(request, cookie, source, snapshot)
                    },
                    onCancel = { viewModel.pauseBatchPreparation() },
                )
            }
        } else {
            CreatorBatchParsingStatus(preparation)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (recordSelectionMode) {
                    recordSelectionMode = false
                    selectedLocalWorkKeys = emptySet()
                } else {
                    viewModel.closeCreator()
                }
            }) {
                Icon(
                    if (recordSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (recordSelectionMode) "退出多选" else "返回作者列表",
                )
            }
            Text(
                if (recordSelectionMode) "已选择 ${selectedLocalWorkKeys.size} 项" else profile.nickname,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (detailTab == 1 && recordSelectionMode) {
                IconButton(onClick = {
                    selectedLocalWorkKeys = if (selectedLocalWorkKeys == selectableLocalWorkKeys) {
                        emptySet()
                    } else {
                        selectableLocalWorkKeys
                    }
                }) {
                    Icon(Icons.Default.SelectAll, contentDescription = "全选作者本地下载")
                }
                IconButton(
                    onClick = { showRecordRedownloadDialog = true },
                    enabled = retryCount > 0 && !state.isStartingBatch,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新下载所选记录")
                }
                IconButton(
                    onClick = { showRecordDeleteDialog = true },
                    enabled = selectedLocalWorkKeys.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "删除所选记录")
                }
            } else if (detailTab == 1) {
                Box {
                    IconButton(onClick = { showLocalSortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序作者本地下载")
                    }
                    DropdownMenu(
                        expanded = showLocalSortMenu,
                        onDismissRequest = { showLocalSortMenu = false },
                    ) {
                        CreatorLocalSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label) },
                                onClick = {
                                    viewModel.updateLocalSort(sort)
                                    showLocalSortMenu = false
                                },
                                leadingIcon = if (sort == state.localSort) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                            )
                        }
                    }
                }
                if (selectableLocalWorkKeys.isNotEmpty()) {
                    IconButton(onClick = { recordSelectionMode = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "批量操作作者本地下载")
                    }
                }
            } else if (detailTab == 0) {
                IconButton(onClick = viewModel::refreshCreator, enabled = !state.isLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新作者主页")
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                CreatorProfileHeader(
                    profile,
                    onStop = { showStopDialog = true },
                    onFollow = { viewModel.followCreator(profile) },
                    actionsEnabled = state.organizingCreatorKey != profile.key,
                )
            }
            state.batchSummaries[profile.key]?.takeIf { it.paused > 0 }?.let { summary ->
                item {
                    if (preparation?.isPaused == true) {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("批量准备已暂停", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "继续后请保持应用在前台；切到后台、锁屏或离开本页会再次暂停。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(onClick = viewModel::resumePausedBatch, enabled = !state.isStartingBatch) {
                                    Text("继续剩余 ${summary.paused} 个作品")
                                }
                            }
                        }
                    } else {
                        OutlinedButton(onClick = viewModel::resumePausedBatch, enabled = !state.isStartingBatch) {
                            Text("继续剩余 ${summary.paused} 个作品")
                        }
                    }
                }
            }
            item {
                PrimaryTabRow(selectedTabIndex = detailTab) {
                    Tab(
                        selected = detailTab == 0,
                        onClick = {
                            detailTab = 0
                            viewModel.saveDetailTab(profile.key, 0)
                            recordSelectionMode = false
                            selectedLocalWorkKeys = emptySet()
                        },
                        text = { Text("公开作品") },
                    )
                    Tab(
                        selected = detailTab == 1,
                        onClick = {
                            detailTab = 1
                            viewModel.saveDetailTab(profile.key, 1)
                        },
                        text = {
                            Text(
                                if (localDownloadBytes > 0L) {
                                    "本地下载 · ${formatBytes(localDownloadBytes)}"
                                } else {
                                    "本地下载"
                                },
                            )
                        },
                    )
                }
            }
            if (state.isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("正在刷新，已缓存内容仍可浏览")
                    }
                }
            }
            if (state.error.isNotBlank()) {
                item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            }
            if (displayedWorks.isEmpty() && !state.isLoading) {
                item {
                    Text(if (detailTab == 0) "当前页没有探测到公开作品" else "还没有本地下载")
                }
            }
            items(displayedWorks, key = CreatorWork::key) { work ->
                if (detailTab == 1 && work.platform == SourcePlatform.BILIBILI && work.relatedTasks.isNotEmpty()) {
                    BilibiliTaskGroupCard(
                        work.relatedTasks,
                        work.key in selectedLocalWorkKeys,
                        recordSelectionMode,
                        emptyMap(),
                        onOpen = { openBilibiliGroup = work.contentId.substringBefore(':') },
                        onToggle = { selectedLocalWorkKeys = toggleTaskSelection(selectedLocalWorkKeys, work.key) },
                        showProgress = false,
                        showPublishedAt = true,
                    )
                    return@items
                }
                CreatorWorkCard(
                    work = work,
                    selected = if (detailTab == 0) {
                        work.key in state.selectedWorkKeys
                    } else {
                        work.key in selectedLocalWorkKeys
                    },
                    selectionEnabled = detailTab == 0 ||
                        recordSelectionMode && work.key in selectableLocalWorkKeys,
                    onToggle = {
                        if (detailTab == 0) {
                            viewModel.toggleWork(work)
                        } else {
                            selectedLocalWorkKeys = toggleTaskSelection(selectedLocalWorkKeys, work.key)
                        }
                    },
                    onManage = work.task?.takeIf { !recordSelectionMode }?.let { task ->
                        {
                            saveCurrentListPosition()
                            onManageTask(task.id)
                        }
                    },
                    onOpen = work.task?.takeIf { detailTab == 1 && !recordSelectionMode }
                        ?.let { task ->
                            {
                                saveCurrentListPosition()
                                onOpenLocalTask(task.id)
                            }
                        },
                    onRetry = work.task?.takeIf {
                        !recordSelectionMode &&
                            it.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)
                    }?.let { task -> { mainViewModel.retryTask(task) } }
                        ?: if (!recordSelectionMode && work.preparationActionable && !state.isStartingBatch) {
                            { viewModel.retryLocalPreparations(setOf(work.key)) }
                        } else null,
                    showLocalProgress = false,
                    trackProgress = null,
                )
            }
            if (detailTab == 0) {
                item {
                    CreatorPageControls(state, viewModel)
                }
            }
        }
        if (detailTab == 0 && profile.platform in CREATOR_BATCH_PLATFORMS) {
            CreatorSelectionBar(state, viewModel, onDownload = { showBatchSettings = true })
        }
    }

    openBilibiliGroup?.let { bv ->
        BilibiliTaskGroupDialog(
            bv,
            profile.key,
            mainViewModel,
            requestAllFilesAccess,
            { taskId ->
                saveCurrentListPosition()
                onManageTask(taskId)
            },
            { openBilibiliGroup = null },
        )
    }
    if (showBatchSettings) {
        BatchSettingsDialog(
            state = state,
            onSettings = viewModel::updateBatchSettings,
            onDismiss = { showBatchSettings = false },
            onConfirm = {
                showBatchSettings = false
                viewModel.startBatch()
            },
        )
    }
    if (showRecordRedownloadDialog) {
        val skipped = selectedRecordTasks.size - selectedRecordRedownloadTasks.size
        AlertDialog(
            onDismissRequest = { showRecordRedownloadDialog = false },
            title = { Text("重新下载所选记录") },
            text = {
                Column {
                    Text("将重新解析并下载 $retryCount 个任务。")
                    if (skipped > 0) Text("另有 $skipped 个任务状态不允许，将自动跳过。")
                    Text("任务文件夹中的其他文件会保留。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedRecordRedownloadTasks.any(mainViewModel::requiresAllFilesAccess)) {
                            requestAllFilesAccess()
                            mainViewModel.showMessage("授权后请再次点击批量重新下载")
                        } else {
                            if (selectedRecordTasks.isNotEmpty()) mainViewModel.retryTasks(selectedRecordTasks)
                            viewModel.retryLocalPreparations(selectedPreparations)
                            recordSelectionMode = false
                            selectedLocalWorkKeys = emptySet()
                        }
                        showRecordRedownloadDialog = false
                    },
                    enabled = retryCount > 0 && !state.isStartingBatch,
                ) { Text("重新下载") }
            },
            dismissButton = {
                TextButton(onClick = { showRecordRedownloadDialog = false }) { Text("取消") }
            },
        )
    }
    if (showRecordDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showRecordDeleteDialog = false
                deleteRecordFiles = false
            },
            title = { Text("删除所选记录") },
            text = {
                Column {
                    Text("确定删除所选的 ${selectedLocalWorks.size} 个本地下载记录吗？")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteRecordFiles,
                            onCheckedChange = { deleteRecordFiles = it },
                        )
                        Text("同时删除下载内容和空任务文件夹")
                    }
                    Text("任务文件夹中的其他文件不会被删除。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (deleteRecordFiles && selectedRecordTasks.any(mainViewModel::requiresAllFilesAccess)) {
                            requestAllFilesAccess()
                            mainViewModel.showMessage("授权后请再次点击批量删除")
                        } else {
                            if (selectedRecordTasks.isNotEmpty()) mainViewModel.deleteTasks(selectedRecordTasks, deleteRecordFiles)
                            viewModel.deleteLocalPreparations(selectedLocalWorkKeys)
                            recordSelectionMode = false
                            selectedLocalWorkKeys = emptySet()
                        }
                        showRecordDeleteDialog = false
                        deleteRecordFiles = false
                    },
                    enabled = selectedLocalWorks.isNotEmpty() && !state.isStartingBatch,
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRecordDeleteDialog = false
                    deleteRecordFiles = false
                }) { Text("取消") }
            },
        )
    }
    if (state.showRiskWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRiskWarning,
            title = { Text("已跨页选择较多作品") },
            text = { Text("连续解析和下载大量作品可能触发平台风控，失败作品会保留并可稍后重试。") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRiskWarning) { Text("我知道了") }
            },
        )
    }
    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(if (profile.archived) "删除作者归档" else "停止关注作者") },
            text = {
                Column {
                    Text(
                        if (profile.archived) "仅删除作者归档记录，本地作品会转入独立作品。"
                        else "已有下载记录时会转入本地归档，文件和历史不会被删除。",
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (profile.archived) {
                        viewModel.deleteArchived(profile)
                    } else {
                        viewModel.stopFollowing(profile)
                    }
                    showStopDialog = false
                }) { Text(if (profile.archived) "删除" else "停止关注") }
            },
            dismissButton = { TextButton(onClick = { showStopDialog = false }) { Text("取消") } },
        )
    }
}
