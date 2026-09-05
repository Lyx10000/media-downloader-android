package com.local.douyindownloader

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun DiagnosticsScreen(
    logText: String,
    isExporting: Boolean,
    viewModel: MainViewModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::exportLogs, enabled = !isExporting) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (isExporting) "正在导出" else "导出 ZIP")
            }
            OutlinedButton(onClick = viewModel::refreshLogs) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("刷新")
            }
            IconButton(onClick = viewModel::clearLogs) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "清理日志")
            }
        }
        Text("日志已自动脱敏，不会上传。默认保留 30 天或 20 MB。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(Modifier.weight(1f)) {
            SelectionContainer {
                Text(
                    logText.ifBlank { "暂无日志" },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
@Composable
internal fun SettingsScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    chooseFolder: () -> Unit,
    requestAllFilesAccess: () -> Unit,
) {
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showGithubWarning by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("下载偏好", style = MaterialTheme.typography.titleMedium) }
        item {
            ListItem(
                headlineContent = { Text("同分辨率优先 H.264") },
                supportingContent = { Text("兼容性更好；关闭时默认选择排序最高档") },
                trailingContent = {
                    Switch(checked = uiState.preferH264, onCheckedChange = viewModel::updatePreferH264)
                },
            )
        }
        item { Text("默认保存模式", style = MaterialTheme.typography.titleMedium) }
        items(videoModes(false)) { (mode, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(uiState.selectedMode == mode) { viewModel.setDefaultMode(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = uiState.selectedMode == mode,
                    onClick = { viewModel.setDefaultMode(mode) },
                )
                Text(label)
            }
        }
        item { HorizontalDivider() }
        item { Text("保存位置", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                uiState.customTreeUri ?: "内部存储/Download/DouyinDownloader/",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = chooseFolder) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("选择目录")
                }
                if (uiState.customTreeUri != null) {
                    OutlinedButton(onClick = { viewModel.setCustomTree(null) }) { Text("恢复默认") }
                }
            }
        }
        item { HorizontalDivider() }
        item { Text("文件管理权限", style = MaterialTheme.typography.titleMedium) }
        item {
            ListItem(
                headlineContent = { Text("所有文件访问") },
                supportingContent = {
                    Text("用于检查和删除默认下载目录中的空任务文件夹")
                },
                trailingContent = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
                        Text("已授权", color = MaterialTheme.colorScheme.primary)
                    } else {
                        OutlinedButton(onClick = requestAllFilesAccess) { Text("授权") }
                    }
                },
            )
        }
        item { HorizontalDivider() }
        item { Text("更新", style = MaterialTheme.typography.titleMedium) }
        item {
            UpdateSettingsCard(
                state = uiState.updateState,
                check = viewModel::checkForUpdates,
                chooseSource = { showSourceDialog = true },
                install = viewModel::requestUpdateInstall,
                cancel = viewModel::cancelUpdateDownload,
                openRelease = viewModel::openUpdateReleasePage,
                useGithub = { showGithubWarning = true },
            )
        }
        item { HorizontalDivider() }
        item {
            Text("版本", style = MaterialTheme.typography.titleMedium)
            Text("应用 ${BuildConfig.VERSION_NAME} · 解析器 $PARSER_VERSION")
        }
    }
    if (showSourceDialog) {
        val release = uiState.updateState.release
        AlertDialog(
            onDismissRequest = { showSourceDialog = false },
            title = { Text("更新到 ${release?.versionName.orEmpty()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(release?.changelog.orEmpty().ifBlank { "暂无更新说明" })
                    release?.asset?.let { asset ->
                        Text(
                            "安装包 ${formatByteSize(asset.sizeBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showSourceDialog = false
                    viewModel.downloadUpdate(UpdateSource.MIRROR)
                }) { Text("镜像加速（推荐）") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showSourceDialog = false }) { Text("取消") }
                    TextButton(onClick = {
                        showSourceDialog = false
                        showGithubWarning = true
                    }) { Text("GitHub 原始源") }
                }
            },
        )
    }
    if (showGithubWarning) {
        AlertDialog(
            onDismissRequest = { showGithubWarning = false },
            title = { Text("使用 GitHub 原始源") },
            text = { Text("部分网络环境未开启代理时，GitHub 下载可能很慢或失败。确定继续吗？") },
            confirmButton = {
                Button(onClick = {
                    showGithubWarning = false
                    viewModel.downloadUpdate(UpdateSource.GITHUB)
                }) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { showGithubWarning = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun UpdateSettingsCard(
    state: UpdateUiState,
    check: () -> Unit,
    chooseSource: () -> Unit,
    install: () -> Unit,
    cancel: () -> Unit,
    openRelease: () -> Unit,
    useGithub: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("应用 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                }
                if (state.status == UpdateStatus.CHECKING || state.status == UpdateStatus.VALIDATING) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            if (state.status == UpdateStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { state.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    buildString {
                        append(formatByteSize(state.downloadedBytes))
                        if (state.totalBytes > 0L) append(" / ${formatByteSize(state.totalBytes)}")
                        if (state.bytesPerSecond > 0L) append(" · ${formatByteSize(state.bytesPerSecond)}/s")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    UpdateStatus.AVAILABLE -> {
                        if (state.release?.asset != null) {
                            Button(onClick = chooseSource) { Text("立即更新") }
                        } else {
                            Button(onClick = openRelease) { Text("打开 Release") }
                        }
                        OutlinedButton(onClick = check) { Text("重新检查") }
                    }
                    UpdateStatus.DOWNLOADING -> OutlinedButton(onClick = cancel) { Text("取消下载") }
                    UpdateStatus.READY_TO_INSTALL -> Button(onClick = install) { Text("安装更新") }
                    UpdateStatus.FAILED -> {
                        OutlinedButton(onClick = check) { Text("重新检查") }
                        if (state.source == UpdateSource.MIRROR && state.release?.asset != null) {
                            Button(onClick = useGithub) { Text("改用 GitHub") }
                        }
                    }
                    UpdateStatus.CHECKING, UpdateStatus.VALIDATING -> Unit
                    UpdateStatus.IDLE, UpdateStatus.UP_TO_DATE ->
                        OutlinedButton(onClick = check) { Text("检查更新") }
                }
            }
        }
    }
}
