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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalDownloadsScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    creatorState: CreatorLibraryUiState,
    creatorViewModel: CreatorLibraryViewModel,
    selectedSection: Int,
    onSelectedSection: (Int) -> Unit,
    chooseFolder: () -> Unit,
    requestAllFilesAccess: () -> Unit,
    onManageTask: (String) -> Unit,
    onOpenLocalTask: (String) -> Unit,
    selectionMode: Boolean,
    selectedTaskIds: Set<String>,
    taskPlatformFilter: SourcePlatform?,
    onTaskPlatformFilter: (SourcePlatform?) -> Unit,
    onToggleTaskSelection: (String) -> Unit,
    focusedTaskId: String?,
    onTaskFocused: () -> Unit,
    onOpenQuestionArchive: (String) -> Unit,
) {
    val creator = creatorState.selectedCreator
    val authorKeys = creatorState.creators.mapTo(hashSetOf(), CreatorProfile::key)
    val localIndependentTasks = uiState.allTasks.filter { task ->
        (task.outputs.isNotEmpty() || task.status == TaskStatus.COMPLETE) &&
            taskCreatorKey(task) !in authorKeys
    }
    DisposableEffect(Unit) {
        viewModel.onTasksVisible()
        onDispose(viewModel::onTasksHidden)
    }
    Column(Modifier.fillMaxSize()) {
        if (creator == null) {
            PrimaryTabRow(selectedTabIndex = selectedSection) {
                Tab(
                    selected = selectedSection == 0,
                    onClick = { onSelectedSection(0) },
                    text = { Text("独立作品") },
                )
                Tab(
                    selected = selectedSection == 1,
                    onClick = { onSelectedSection(1) },
                    text = { Text("作者") },
                )
            }
        }
        when {
            creator != null -> CreatorDetailScreen(
                creatorState,
                creatorViewModel,
                viewModel,
                onManageTask,
                onOpenLocalTask,
                requestAllFilesAccess,
            )
            selectedSection == 0 -> TasksScreen(
                tasks = localIndependentTasks,
                questionArchives = uiState.questionArchives,
                viewModel = viewModel,
                chooseFolder = chooseFolder,
                requestAllFilesAccess = requestAllFilesAccess,
                onManageTask = onManageTask,
                selectionMode = selectionMode,
                selectedTaskIds = selectedTaskIds,
                platformFilter = taskPlatformFilter,
                onPlatformFilter = onTaskPlatformFilter,
                onToggleTaskSelection = onToggleTaskSelection,
                focusedTaskId = focusedTaskId,
                onTaskFocused = onTaskFocused,
                onOpenQuestionArchive = onOpenQuestionArchive,
                showAdaptiveStatus = false,
                allowPreview = true,
                showTransferControls = false,
                emptyText = "还没有独立作品",
            )
            else -> CreatorLibraryScreen(
                state = creatorState,
                viewModel = creatorViewModel,
                requestAllFilesAccess = requestAllFilesAccess,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CreatorLibraryScreen(
    state: CreatorLibraryUiState,
    viewModel: CreatorLibraryViewModel,
    requestAllFilesAccess: () -> Unit,
) {
    val visibleCreators = filterCreatorsByPlatform(state.creators, state.platformFilter)
    val active = visibleCreators.filterNot(CreatorProfile::archived)
    val archived = visibleCreators.filter(CreatorProfile::archived)
    var selectionMode by remember { mutableStateOf(false) }
    var selectedCreatorKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val selectableKeys = visibleCreators.mapTo(linkedSetOf(), CreatorProfile::key)
    val selectedProfiles = visibleCreators.filter { it.key in selectedCreatorKeys }
    val selectedActive = selectedProfiles.filterNot(CreatorProfile::archived)
    val selectedArchived = selectedProfiles.filter(CreatorProfile::archived)
    val selectedTaskCount = selectedActive.sumOf { state.taskSummaries[it.key]?.total ?: 0 }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedCreatorKeys = emptySet()
        showDeleteDialog = false
    }
    LaunchedEffect(selectableKeys) {
        selectedCreatorKeys = reconcileTaskSelection(selectedCreatorKeys, selectableKeys)
        if (selectableKeys.isEmpty()) {
            selectionMode = false
            showDeleteDialog = false
        }
    }
    if (state.creators.none { it.platform in CREATOR_LIBRARY_PLATFORMS }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有关注的作者")
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.platformFilter == null,
                        onClick = { viewModel.setPlatformFilter(null) },
                        label = { Text("全部") },
                    )
                    CREATOR_LIBRARY_PLATFORMS.forEach { platform ->
                        FilterChip(
                            selected = state.platformFilter == platform,
                            onClick = { viewModel.setPlatformFilter(platform) },
                            label = { Text(platform.displayName) },
                        )
                    }
                }
                IconButton(
                    enabled = visibleCreators.isNotEmpty() && !state.isDeletingCreators,
                    onClick = {
                        selectionMode = !selectionMode
                        selectedCreatorKeys = emptySet()
                        showDeleteDialog = false
                    },
                ) {
                    Icon(
                        if (selectionMode) Icons.Default.Close else Icons.Default.DeleteSweep,
                        contentDescription = if (selectionMode) "退出作者批量管理" else "批量删除作者",
                    )
                }
            }
        }
        if (selectionMode) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("已选择 ${selectedCreatorKeys.size} 位", modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                selectedCreatorKeys = if (selectedCreatorKeys.size == selectableKeys.size) {
                                    emptySet()
                                } else {
                                    selectableKeys
                                }
                            },
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "全选当前平台作者")
                        }
                        IconButton(
                            enabled = selectedCreatorKeys.isNotEmpty(),
                            onClick = { showDeleteDialog = true },
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除选中作者")
                        }
                    }
                }
            }
        }
        if (state.isDeletingCreators) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("正在按顺序删除作者内容…")
                }
            }
        }
        if (visibleCreators.isEmpty()) {
            item {
                Text(
                    "当前平台还没有作者记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(active, key = CreatorProfile::key) { profile ->
            CreatorCard(
                profile = profile,
                taskSummary = state.taskSummaries[profile.key],
                onClick = {
                    if (selectionMode) {
                        selectedCreatorKeys = toggleTaskSelection(selectedCreatorKeys, profile.key)
                    } else {
                        viewModel.openCreator(profile.key)
                    }
                },
                selectionMode = selectionMode,
                selected = profile.key in selectedCreatorKeys,
                onSelectedChange = {
                    selectedCreatorKeys = toggleTaskSelection(selectedCreatorKeys, profile.key)
                },
            )
        }
        if (archived.isNotEmpty()) {
            item {
                Text(
                    "已失效 / 本地归档",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(archived, key = CreatorProfile::key) { profile ->
                CreatorCard(
                    profile = profile,
                    taskSummary = state.taskSummaries[profile.key],
                    onClick = {
                        if (selectionMode) {
                            selectedCreatorKeys = toggleTaskSelection(selectedCreatorKeys, profile.key)
                        } else {
                            viewModel.openCreator(profile.key)
                        }
                    },
                    archived = true,
                    selectionMode = selectionMode,
                    selected = profile.key in selectedCreatorKeys,
                    onSelectedChange = {
                        selectedCreatorKeys = toggleTaskSelection(selectedCreatorKeys, profile.key)
                    },
                )
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除选中的作者？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedActive.isNotEmpty()) {
                        Text(
                            "${selectedActive.size} 位普通作者：彻底删除作者资料、$selectedTaskCount 个任务和全部下载文件。",
                        )
                    }
                    if (selectedArchived.isNotEmpty()) {
                        Text(
                            "${selectedArchived.size} 位归档作者：只移除归档资料和主页缓存，独立任务及本地文件保留。",
                        )
                    }
                    Text("删除会逐位执行；未能完整删除的普通作者会保留，方便重试。")
                    if (selectedActive.isNotEmpty()) {
                        Text("如跳转到文件访问权限设置，授权返回后请再次点击删除。")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = selectedCreatorKeys.isNotEmpty() && !state.isDeletingCreators,
                    onClick = {
                        if (viewModel.bulkDeleteRequiresAllFilesAccess(selectedCreatorKeys)) {
                            requestAllFilesAccess()
                        } else {
                            viewModel.deleteCreators(selectedCreatorKeys)
                            showDeleteDialog = false
                            selectionMode = false
                            selectedCreatorKeys = emptySet()
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CreatorCard(
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
private fun CreatorDetailScreen(
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
    val displayedWorks = if (detailTab == 0) state.pageWorks else {
        state.allWorks.filter(CreatorWork::hasLocalContent)
            .sortedByDescending { it.task?.createdAt ?: 0L }
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
            } else if (detailTab == 1 && selectableLocalWorkKeys.isNotEmpty()) {
                IconButton(onClick = { recordSelectionMode = true }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "批量操作作者本地下载")
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
                            viewModel.saveDetailListPosition(
                                profile.key,
                                detailTab,
                                listState.firstVisibleItemIndex,
                                listState.firstVisibleItemScrollOffset,
                            )
                            onManageTask(task.id)
                        }
                    },
                    onOpen = work.task?.takeIf { detailTab == 1 && !recordSelectionMode }
                        ?.let { task -> { onOpenLocalTask(task.id) } },
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
        BilibiliTaskGroupDialog(bv, profile.key, mainViewModel, requestAllFilesAccess, onManageTask, { openBilibiliGroup = null })
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreatorProfileHeader(
    profile: CreatorProfile,
    onStop: () -> Unit,
    onFollow: () -> Unit,
    actionsEnabled: Boolean,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = "${profile.nickname}头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformBrandBadge(profile.platform)
                        Spacer(Modifier.size(8.dp))
                        Text(profile.nickname, style = MaterialTheme.typography.titleLarge)
                    }
                    profile.accountId.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(creatorStatusLabel(profile), style = MaterialTheme.typography.bodySmall)
                }
            }
            profile.bio.takeIf(String::isNotBlank)?.let { Text(it) }
            profile.location.takeIf(String::isNotBlank)?.let {
                Text("IP 属地：$it", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.metrics.forEach { metric ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text("${metric.label} ${metric.value}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            Text(
                if (profile.refreshedAt > 0L) {
                    "上次更新：${formatCreatorTime(profile.refreshedAt)}"
                } else "尚未刷新完整主页资料",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.archived) {
                    Button(onClick = onFollow, enabled = actionsEnabled) { Text("重新关注") }
                }
                TextButton(onClick = onStop, enabled = actionsEnabled) {
                    Text(if (profile.archived) "删除归档" else "停止关注")
                }
            }
        }
    }
}

@Composable
private fun CreatorWorkCard(
    work: CreatorWork,
    selected: Boolean,
    selectionEnabled: Boolean,
    onToggle: () -> Unit,
    onManage: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    showLocalProgress: Boolean = false,
    trackProgress: TaskTrackDownloadProgress? = null,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().then(if (selectionEnabled) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionEnabled) Checkbox(checked = selected, onCheckedChange = { onToggle() })
            AsyncImage(
                model = work.coverUrl,
                contentDescription = "作品预览图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(76.dp).clip(MaterialTheme.shapes.medium),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(work.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mediaKindLabel(work.kind), style = MaterialTheme.typography.labelSmall)
                    Text(
                        localWorkStatusLabel(work.localStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (work.localStatus == CreatorWorkLocalStatus.AVAILABLE) {
                            MaterialTheme.colorScheme.primary
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (work.remoteStatus != CreatorWorkRemoteStatus.PUBLIC) {
                    Text(
                        remoteWorkStatusLabel(work.remoteStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (work.localStatus == CreatorWorkLocalStatus.AVAILABLE) {
                    Text("本地已有，当前页全选会跳过", style = MaterialTheme.typography.bodySmall)
                }
                if (showLocalProgress) {
                    val task = work.task
                    if (task != null && task.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                        Text(task.stage, style = MaterialTheme.typography.bodySmall)
                        if (task.status == TaskStatus.RUNNING && trackProgress != null) {
                            TrackDownloadProgressBars(trackProgress)
                        } else if (task.status == TaskStatus.RUNNING && shouldShowDownloadProgress(task.stage)) {
                            LinearProgressIndicator(
                                progress = { task.progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else if (work.preparationFailed) {
                        Text(work.preparation?.error.orEmpty().ifBlank { "作品准备失败，可重试" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else if (task == null && work.preparation != null) {
                        Text(when (work.preparation.status) {
                            CreatorBatchWorkStatus.PAUSED -> "准备已暂停"
                            CreatorBatchWorkStatus.PARSING_HTTP, CreatorBatchWorkStatus.PARSING -> "正在准备作品"
                            else -> "等待作品准备"
                        },
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (onOpen != null || onManage != null || onRetry != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onOpen?.let { action ->
                            TextButton(onClick = action) { Text("预览") }
                        }
                        onManage?.let { action ->
                            TextButton(onClick = action) { Text("管理文件") }
                        }
                        onRetry?.let { action ->
                            TextButton(onClick = action) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatorPageControls(
    state: CreatorLibraryUiState,
    viewModel: CreatorLibraryViewModel,
) {
    val skipped = state.pageWorks.count(CreatorWork::shouldSkipInSelectAll)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::previousPage, enabled = state.hasPrevious && !state.isLoading) {
                Text("上一页")
            }
            Text("第 ${state.pageNumber} 页")
            OutlinedButton(onClick = viewModel::nextPage, enabled = state.hasMore && !state.isLoading) {
                Text("下一页")
            }
        }
        Button(onClick = viewModel::selectCurrentPage, enabled = state.pageWorks.isNotEmpty()) {
            Text("全选当前页")
        }
        Text(
            "本页 ${state.pageWorks.size} 个，已下载会跳过 $skipped 个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.selectedCreator?.platform == SourcePlatform.XIAOHONGSHU) {
            Text(
                "小红书当前展示网页首屏可稳定获取的公开作品；平台要求后续请求使用页面运行时签名，因此暂不提供不可靠的翻页入口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CreatorSelectionBar(
    state: CreatorLibraryUiState,
    viewModel: CreatorLibraryViewModel,
    onDownload: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("已选 ${state.selectedWorkKeys.size} 个", fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.selectedWorkKeys.isEmpty()) "选择作品后估算空间"
                    else "约 ${formatBytes(state.estimate.minimumBytes)}–${formatBytes(state.estimate.maximumBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.estimate.estimatedItems > 0) {
                    Text(
                        "${state.estimate.estimatedItems} 个作品按类型和时长粗略估算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (state.availableBytes == null) "存储提供方未提供容量"
                    else "可用 ${formatBytes(state.availableBytes)} / 总计 ${formatBytes(state.totalBytes ?: 0L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.availableBytes != null &&
                    state.estimate.maximumBytes >= state.availableBytes
                ) {
                    Text(
                        "估算上限可能超过剩余空间，请减少选择或更换保存位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = viewModel::clearSelection, enabled = state.selectedWorkKeys.isNotEmpty()) {
                Text("清空")
            }
            Button(
                onClick = onDownload,
                enabled = state.selectedWorkKeys.isNotEmpty() && !state.isStartingBatch,
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("下载")
            }
        }
    }
}

@Composable
private fun BatchSettingsDialog(
    state: CreatorLibraryUiState,
    onSettings: (BatchDownloadSettings) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val settings = state.batchSettings
    val selectedKinds = state.allWorks.asSequence()
        .filter { it.key in state.selectedWorkKeys }
        .map(CreatorWork::kind)
        .toSet()
    val hasVideos = MediaKind.VIDEO in selectedKinds
    val hasImages = MediaKind.IMAGE in selectedKinds
    val hasDocuments = MediaKind.DOCUMENT in selectedKinds
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本批次下载设置") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.selectedCreator?.platform == SourcePlatform.BILIBILI) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(settings.bilibiliAllParts, { onSettings(settings.copy(bilibiliAllParts = it)) })
                            Text("多P稿件下载全部P（关闭则仅P1）")
                        }
                        Text("每个稿件显示一张卡，各P独立保存；实际下载数量可能多于勾选的稿件数。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.selectedCreator?.platform == SourcePlatform.XIAOHONGSHU) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(
                                "部分小红书作品可能需要页面环境准备。准备期间请保持应用在前台；" +
                                    "切到后台、锁屏或离开作者页面会暂停，返回后可以继续。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (hasVideos) {
                    item { Text("视频清晰度", fontWeight = FontWeight.SemiBold) }
                    items(BatchVideoQuality.entries) { quality ->
                        SettingRadioRow(
                            selected = settings.quality == quality,
                            label = when (quality) {
                                BatchVideoQuality.HIGHEST -> "最高可用档位"
                                BatchVideoQuality.UP_TO_1080P -> "最高不超过 1080p"
                                BatchVideoQuality.UP_TO_720P -> "最高不超过 720p"
                            },
                            onClick = { onSettings(settings.copy(quality = quality)) },
                        )
                    }
                    item {
                        Text(
                            "音视频处理",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(
                        listOf(
                            DownloadMode.MERGE_KEEP,
                            DownloadMode.MP4_ONLY,
                            DownloadMode.TRACKS,
                            DownloadMode.VIDEO_ONLY,
                            DownloadMode.AUDIO_ONLY,
                        ),
                    ) { mode ->
                        SettingRadioRow(
                            selected = settings.mode == mode,
                            label = when (mode) {
                                DownloadMode.MERGE_KEEP -> "合成并保留原视频和音频"
                                DownloadMode.MP4_ONLY -> "仅保留成品 MP4"
                                DownloadMode.TRACKS -> "分别下载视频和音频"
                                DownloadMode.VIDEO_ONLY -> "仅视频"
                                DownloadMode.AUDIO_ONLY -> "仅音频"
                            },
                            onClick = { onSettings(settings.copy(mode = mode)) },
                        )
                    }
                }
                if (hasImages) {
                    item {
                        BatchFixedPolicy(
                            title = "图片/图文",
                            description = "下载全部最高可用图片；实况照片和背景音乐存在时一并保留。",
                        )
                    }
                }
                if (hasDocuments) {
                    item {
                        BatchFixedPolicy(
                            title = "知乎文章/回答",
                            description = "保存 Markdown，并下载正文图片和内嵌视频。",
                        )
                    }
                }
                if (hasVideos && selectedKinds.size > 1) {
                    item {
                        Text(
                            "上方清晰度和音视频选项只作用于视频作品。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (selectedKinds.isEmpty()) {
                    item {
                        Text(
                            "没有找到已选作品，请返回后重新选择。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = selectedKinds.isNotEmpty()) { Text("开始下载") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CreatorBatchParsingStatus(preparation: CreatorBatchPreparation) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "正在读取小红书作品 " +
                "${(preparation.processed + 1).coerceAtMost(preparation.total.coerceAtLeast(1))}/" +
                "${preparation.total}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "页面状态已取得，正在生成下载任务，请稍候。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatchFixedPolicy(title: String, description: String) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingRadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private fun creatorStatusLabel(profile: CreatorProfile): String = when {
    profile.archived -> "本地归档"
    profile.accountStatus == CreatorAccountStatus.PUBLIC -> "公开主页"
    profile.accountStatus == CreatorAccountStatus.RESTRICTED -> "主页受限或私密"
    profile.accountStatus == CreatorAccountStatus.DEACTIVATED -> "账号已注销"
    profile.accountStatus == CreatorAccountStatus.REFRESH_FAILED -> "刷新失败，显示缓存"
    profile.accountStatus == CreatorAccountStatus.INACCESSIBLE -> "暂不可访问"
    else -> "状态待确认"
}

private fun mediaKindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.VIDEO -> "视频"
    MediaKind.IMAGE -> "图文"
    MediaKind.DOCUMENT -> "文章"
}

private fun localWorkStatusLabel(status: CreatorWorkLocalStatus): String = when (status) {
    CreatorWorkLocalStatus.NOT_DOWNLOADED -> "未下载"
    CreatorWorkLocalStatus.QUEUED -> "等待下载"
    CreatorWorkLocalStatus.DOWNLOADING -> "下载中"
    CreatorWorkLocalStatus.AVAILABLE -> "已下载"
    CreatorWorkLocalStatus.PARTIAL -> "部分文件缺失"
    CreatorWorkLocalStatus.DELETED -> "本地已删除"
    CreatorWorkLocalStatus.FAILED -> "下载失败"
}

private fun remoteWorkStatusLabel(status: CreatorWorkRemoteStatus): String = when (status) {
    CreatorWorkRemoteStatus.PUBLIC -> "公开可见"
    CreatorWorkRemoteStatus.NOT_DETECTED -> "本次公开页未探测到，本地记录仍保留"
    CreatorWorkRemoteStatus.UNAVAILABLE -> "作品已明确不可访问，本地文件仍保留"
    CreatorWorkRemoteStatus.CHECK_FAILED -> "远端检查失败，本地记录仍保留"
    CreatorWorkRemoteStatus.UNKNOWN -> "远端状态待确认"
}

private fun formatCreatorTime(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(value))

internal fun formatBytes(value: Long): String = when {
    value <= 0L -> "0 MB"
    value >= 1024L * 1024L * 1024L -> "%.1f GB".format(Locale.US, value / (1024.0 * 1024.0 * 1024.0))
    else -> "%.1f MB".format(Locale.US, value / (1024.0 * 1024.0))
}
