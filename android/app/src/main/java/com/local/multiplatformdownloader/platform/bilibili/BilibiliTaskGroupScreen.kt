package com.local.multiplatformdownloader.platform.bilibili

import com.local.multiplatformdownloader.core.model.FileState
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.download.TaskTrackDownloadProgress
import com.local.multiplatformdownloader.feature.home.MainViewModel
import com.local.multiplatformdownloader.feature.tasks.TasksScreen
import com.local.multiplatformdownloader.feature.tasks.TrackDownloadProgressBars
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun BilibiliTaskGroupCard(tasks: List<TaskRecord>, selected: Boolean = false,
    selectionMode: Boolean = false,
    trackProgress: Map<String, TaskTrackDownloadProgress> = emptyMap(),
    onOpen: () -> Unit,
    onToggle: () -> Unit = {},
) {
    if (tasks.isEmpty()) return
    val completed = tasks.count { it.status == TaskStatus.COMPLETE && it.fileState == FileState.AVAILABLE }
    val active = tasks.firstOrNull { it.status == TaskStatus.RUNNING }
    val failed = tasks.count { it.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED) }
    OutlinedCard(Modifier.fillMaxWidth().clickable { if (selectionMode) onToggle() else onOpen() }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) Checkbox(selected, null)
                Text(tasks.first().bilibiliTitle.ifBlank { tasks.first().title.substringBefore(" · P") }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                PlatformBrandBadge(SourcePlatform.BILIBILI)
            }
            Text("${tasks.first().author} · ${tasks.size} 个分P任务", style = MaterialTheme.typography.bodySmall)
            Text("$completed 个已完成 · $failed 个失败或取消 · 点击管理分P", style = MaterialTheme.typography.bodySmall)
            if (active != null) {
                Text(active.stage, style = MaterialTheme.typography.bodySmall)
                trackProgress[active.id]?.let { TrackDownloadProgressBars(it) }
                    ?: LinearProgressIndicator(progress = { active.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun BilibiliTaskGroupDialog(bvid: String, authorKey: String?, viewModel: MainViewModel,
    requestAllFilesAccess: () -> Unit, onManageTask: (String) -> Unit, onDismiss: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tasks = uiState.allTasks.filter { bilibiliGroupKey(it) == bvid &&
        (if (authorKey == null) !it.creatorChild else it.authorKey == authorKey) }
        .sortedWith(compareBy<TaskRecord> { it.bilibiliPage }.thenBy { it.createdAt })
    var deleteAll by remember { mutableStateOf(false) }
    var deleteFiles by remember { mutableStateOf(false) }
    DisposableEffect(bvid, authorKey) {
        onDispose {
            val id = viewModel.expandedTaskId.value
            val task = viewModel.uiState.value.allTasks.firstOrNull { it.id == id }
            if (task != null && bilibiliGroupKey(task) == bvid &&
                (if (authorKey == null) !task.creatorChild else task.authorKey == authorKey)) {
                viewModel.toggleTaskPreview(task.id)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("返回") }
                    Text("分P任务 · $bvid", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { deleteAll = true }, enabled = tasks.isNotEmpty()) { Text("删除稿件") }
                }
                HorizontalDivider()
                TasksScreen(tasks, emptyMap(), viewModel, {}, requestAllFilesAccess, onManageTask,
                    false, emptySet(), null, {}, {}, null, {}, {}, groupBilibili = false)
            }
        }
        if (deleteAll) AlertDialog(onDismissRequest = { deleteAll = false }, title = { Text("删除整个稿件的任务？") },
            text = { Column { Text("将删除此稿件当前的 ${tasks.size} 个分P任务。")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteFiles, { deleteFiles = it }); Text("同时删除下载文件及任务目录") } } },
            confirmButton = { TextButton(onClick = {
                if (deleteFiles && tasks.any(viewModel::requiresAllFilesAccess)) requestAllFilesAccess()
                else { viewModel.deleteTasks(tasks, deleteFiles); deleteAll = false; onDismiss() }
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteAll = false }) { Text("取消") } })
    }
}
