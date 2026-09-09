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
    authorSelectionMode: Boolean,
    selectedCreatorKeys: Set<String>,
    showCreatorDeleteDialog: Boolean,
    onAuthorSelectionMode: (Boolean) -> Unit,
    onSelectedCreatorKeys: (Set<String>) -> Unit,
    onShowCreatorDeleteDialog: (Boolean) -> Unit,
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
                showPublishedAt = true,
                emptyText = "还没有独立作品",
            )
            else -> CreatorLibraryScreen(
                state = creatorState,
                viewModel = creatorViewModel,
                requestAllFilesAccess = requestAllFilesAccess,
                selectionMode = authorSelectionMode,
                selectedCreatorKeys = selectedCreatorKeys,
                showDeleteDialog = showCreatorDeleteDialog,
                onSelectionMode = onAuthorSelectionMode,
                onSelectedCreatorKeys = onSelectedCreatorKeys,
                onShowDeleteDialog = onShowCreatorDeleteDialog,
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
    selectionMode: Boolean,
    selectedCreatorKeys: Set<String>,
    showDeleteDialog: Boolean,
    onSelectionMode: (Boolean) -> Unit,
    onSelectedCreatorKeys: (Set<String>) -> Unit,
    onShowDeleteDialog: (Boolean) -> Unit,
) {
    val visibleCreators = filterCreatorsByPlatform(state.creators, state.platformFilter)
    val active = visibleCreators.filterNot(CreatorProfile::archived)
    val archived = visibleCreators.filter(CreatorProfile::archived)
    val selectableKeys = visibleCreators.mapTo(linkedSetOf(), CreatorProfile::key)
    val selectedProfiles = visibleCreators.filter { it.key in selectedCreatorKeys }
    val selectedActive = selectedProfiles.filterNot(CreatorProfile::archived)
    val selectedArchived = selectedProfiles.filter(CreatorProfile::archived)
    val selectedTaskCount = selectedActive.sumOf { state.taskSummaries[it.key]?.total ?: 0 }
    val savedListPosition = remember(state.platformFilter) {
        viewModel.creatorListPosition(state.platformFilter)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedListPosition.index,
        initialFirstVisibleItemScrollOffset = savedListPosition.offset,
    )
    val listItemCount = 1 +
        (if (state.isDeletingCreators) 1 else 0) +
        (if (visibleCreators.isEmpty()) 1 else 0) +
        active.size +
        (if (archived.isNotEmpty()) 1 + archived.size else 0)

    BackHandler(enabled = selectionMode) {
        onSelectionMode(false)
        onSelectedCreatorKeys(emptySet())
        onShowDeleteDialog(false)
    }
    LaunchedEffect(selectableKeys) {
        onSelectedCreatorKeys(reconcileTaskSelection(selectedCreatorKeys, selectableKeys))
        if (selectableKeys.isEmpty()) {
            onSelectionMode(false)
            onShowDeleteDialog(false)
        }
    }
    LaunchedEffect(state.platformFilter) {
        val position = viewModel.creatorListPosition(state.platformFilter)
        listState.scrollToItem(
            index = position.index.coerceIn(0, (listItemCount - 1).coerceAtLeast(0)),
            scrollOffset = position.offset,
        )
    }
    DisposableEffect(state.platformFilter, listState) {
        onDispose {
            viewModel.saveCreatorListPosition(
                platform = state.platformFilter,
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
            )
        }
    }
    if (state.creators.none { it.platform in CREATOR_LIBRARY_PLATFORMS }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有关注的作者")
        }
        return
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PlatformFilterBar(
                selected = state.platformFilter,
                onSelected = viewModel::setPlatformFilter,
            )
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
                        onSelectedCreatorKeys(toggleTaskSelection(selectedCreatorKeys, profile.key))
                    } else {
                        viewModel.openCreator(profile.key)
                    }
                },
                selectionMode = selectionMode,
                selected = profile.key in selectedCreatorKeys,
                onSelectedChange = {
                    onSelectedCreatorKeys(toggleTaskSelection(selectedCreatorKeys, profile.key))
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
                            onSelectedCreatorKeys(toggleTaskSelection(selectedCreatorKeys, profile.key))
                        } else {
                            viewModel.openCreator(profile.key)
                        }
                    },
                    archived = true,
                    selectionMode = selectionMode,
                    selected = profile.key in selectedCreatorKeys,
                    onSelectedChange = {
                        onSelectedCreatorKeys(toggleTaskSelection(selectedCreatorKeys, profile.key))
                    },
                )
            }
        }
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { onShowDeleteDialog(false) },
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
                            onShowDeleteDialog(false)
                            onSelectionMode(false)
                            onSelectedCreatorKeys(emptySet())
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { onShowDeleteDialog(false) }) { Text("取消") }
            },
        )
    }
}
