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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun DiagnosticsScreen(
    logText: String,
    viewModel: MainViewModel,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::exportLogs) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("导出 ZIP")
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
    var showWebViewPlatform by remember { mutableStateOf<SourcePlatform?>(null) }
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
        item { Text("解析环境", style = MaterialTheme.typography.titleMedium) }
        item {
            Button(onClick = { showWebViewPlatform = SourcePlatform.DOUYIN }) {
                Text("登录或刷新抖音环境")
            }
        }
        item {
            OutlinedButton(onClick = { showWebViewPlatform = SourcePlatform.XIAOHONGSHU }) {
                Text("登录或刷新小红书环境")
            }
        }
        item { HorizontalDivider() }
        item {
            Text("版本", style = MaterialTheme.typography.titleMedium)
            Text("应用 ${BuildConfig.VERSION_NAME} · 解析器 android-core-5")
            Text("完全本地运行，不使用服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    showWebViewPlatform?.let { platform ->
        FullScreenWebEnvironment(
            platform = platform,
            onDismiss = { showWebViewPlatform = null },
        )
    }
}
