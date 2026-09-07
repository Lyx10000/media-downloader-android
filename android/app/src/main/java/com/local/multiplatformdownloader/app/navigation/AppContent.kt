package com.local.multiplatformdownloader.app.navigation

import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.update.UpdateLaunchRequest
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryViewModel
import com.local.multiplatformdownloader.feature.creator.TaskHubScreen
import com.local.multiplatformdownloader.feature.document.DocumentReaderScreen
import com.local.multiplatformdownloader.feature.home.FullScreenWebEnvironment
import com.local.multiplatformdownloader.feature.home.HomeScreen
import com.local.multiplatformdownloader.feature.home.MainViewModel
import com.local.multiplatformdownloader.feature.settings.DiagnosticsScreen
import com.local.multiplatformdownloader.feature.settings.SettingsScreen
import com.local.multiplatformdownloader.feature.tasks.ShareableFile
import com.local.multiplatformdownloader.feature.tasks.TaskFileManagerScreen
import com.local.multiplatformdownloader.feature.tasks.buildFileShareChooser
import com.local.multiplatformdownloader.feature.tasks.buildFileShareIntent
import com.local.multiplatformdownloader.feature.tasks.isTaskRedownloadEligible
import com.local.multiplatformdownloader.feature.tasks.reconcileTaskSelection
import com.local.multiplatformdownloader.feature.tasks.toggleTaskSelection
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveScreen
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveViewModel
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState

import com.local.multiplatformdownloader.R

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest

private data class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloaderApp(
    viewModel: MainViewModel,
    creatorViewModel: CreatorLibraryViewModel,
    questionArchiveViewModel: ZhihuQuestionArchiveViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val creatorState by creatorViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var destination by remember { mutableIntStateOf(0) }
    var managedTaskId by remember { mutableStateOf<String?>(null) }
    var readerTaskId by remember { mutableStateOf<String?>(null) }
    var readerOutputUri by remember { mutableStateOf<String?>(null) }
    var questionArchiveTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var loginPlatform by rememberSaveable { mutableStateOf<SourcePlatform?>(null) }
    var taskSelectionMode by remember { mutableStateOf(false) }
    var taskSection by rememberSaveable { mutableIntStateOf(0) }
    var taskPlatformFilterWire by rememberSaveable { mutableStateOf("") }
    var focusedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTaskIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showBatchRedownloadDialog by remember { mutableStateOf(false) }
    var batchDeleteFiles by remember { mutableStateOf(false) }
    val destinations = remember {
        listOf(
            Destination("首页", Icons.Default.Home),
            Destination("任务", Icons.Default.Download),
            Destination("诊断", Icons.Default.BugReport),
            Destination("设置", Icons.Default.Settings),
        )
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setCustomTree(uri)
        }
    }
    val allFilesAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onAppForeground()
    }
    val requestAllFilesAccess = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { allFilesAccess.launch(appIntent) }.onFailure {
                allFilesAccess.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(uiState.message) {
        if (uiState.message.isNotBlank()) snackbar.showSnackbar(viewModel.consumeMessage())
    }
    LaunchedEffect(viewModel) {
        viewModel.diagnosticExports.collectLatest { export ->
            val target = buildFileShareIntent(
                listOf(
                    ShareableFile(
                        uri = Uri.parse(export.uri),
                        displayName = export.displayName,
                        mimeType = "application/zip",
                    ),
                ),
            )
            val opened = target != null && runCatching {
                context.startActivity(buildFileShareChooser(target))
            }.isSuccess
            if (!opened) {
                viewModel.showMessage(
                    "诊断包已保存到 ${export.relativePath}/${export.displayName}，但无法打开系统分享面板",
                )
            }
        }
    }
    LaunchedEffect(creatorViewModel) {
        creatorViewModel.messages.collectLatest(viewModel::showMessage)
    }
    LaunchedEffect(viewModel) {
        viewModel.updateLaunchRequests.collectLatest { request ->
            runCatching { context.startActivity(request.intent) }.onFailure { error ->
                viewModel.showMessage(
                    "无法打开${if (request is UpdateLaunchRequest.InstallApk) "系统安装器" else "目标页面"}：" +
                        Redactor.sanitize(error.message ?: error.javaClass.simpleName),
                )
            }
        }
    }
    val taskPlatformFilter = SourcePlatform.entries.firstOrNull {
        it.wireValue == taskPlatformFilterWire
    }
    val visibleTasks = if (taskPlatformFilter == null) uiState.tasks else {
        uiState.tasks.filter { it.platform == taskPlatformFilter }
    }
    val selectableTaskIds = visibleTasks
        .filter { it.status != TaskStatus.DELETING }
        .mapTo(linkedSetOf(), TaskRecord::id)
    LaunchedEffect(destination, taskSection, selectableTaskIds) {
        selectedTaskIds = reconcileTaskSelection(selectedTaskIds, selectableTaskIds)
        if (destination != 1 || taskSection != 0 || selectableTaskIds.isEmpty()) {
            taskSelectionMode = false
            selectedTaskIds = emptySet()
            showBatchDeleteDialog = false
            showBatchRedownloadDialog = false
            batchDeleteFiles = false
        }
    }
    val selectedTasks = uiState.tasks.filter { it.id in selectedTaskIds }
    val selectedRedownloadTasks = selectedTasks.filter(::isTaskRedownloadEligible)

    val openIndependentTasks: (String?) -> Unit = { taskId ->
        creatorViewModel.closeCreator()
        taskSection = 0
        taskPlatformFilterWire = ""
        taskSelectionMode = false
        selectedTaskIds = emptySet()
        focusedTaskId = taskId
        destination = 1
    }

    val openLoginEnvironment: (SourcePlatform) -> Unit = { platform ->
        viewModel.onLoginEnvironmentOpened(platform)
        loginPlatform = platform
    }
    val activeLoginPlatform = loginPlatform
    if (activeLoginPlatform != null) {
        FullScreenWebEnvironment(
            platform = activeLoginPlatform,
            credentialState = uiState.platformCredentialStates[activeLoginPlatform]
                ?: PlatformCredentialState.NOT_DETECTED,
            snackbarHostState = snackbar,
            onDismiss = {
                viewModel.onLoginEnvironmentClosed(activeLoginPlatform)
                loginPlatform = null
            },
            onPageFinished = { url ->
                viewModel.onLoginPageFinished(activeLoginPlatform, url)
            },
            onLoginAssistResult = { url, result ->
                viewModel.onLoginAssistResult(activeLoginPlatform, url, result)
            },
            onPageError = { url, code, description ->
                viewModel.onLoginPageError(activeLoginPlatform, url, code, description)
            },
            onExternalNavigationFailed = viewModel::showMessage,
        )
        return
    }

    val managedTask = managedTaskId?.let { taskId ->
        uiState.allTasks.firstOrNull { it.id == taskId }
    }
    val readerTask = readerTaskId?.let { taskId ->
        uiState.allTasks.firstOrNull { it.id == taskId }
    }
    if (readerTaskId != null && readerTask == null) {
        LaunchedEffect(readerTaskId) {
            readerTaskId = null
            readerOutputUri = null
        }
    }
    if (readerTask != null) {
        DocumentReaderScreen(
            task = readerTask,
            selectedOutput = readerTask.outputs.firstOrNull { it.uri == readerOutputUri },
            viewModel = viewModel,
            onBack = {
                readerTaskId = null
                readerOutputUri = null
            },
        )
        return
    }
    if (managedTaskId != null && managedTask == null) {
        LaunchedEffect(managedTaskId) { managedTaskId = null }
    }
    if (managedTask != null) {
        TaskFileManagerScreen(
            task = managedTask,
            viewModel = viewModel,
            snackbarHostState = snackbar,
            onOpenDocument = { output ->
                readerOutputUri = output.uri
                readerTaskId = managedTask.id
            },
            onBack = { managedTaskId = null },
        )
        return
    }
    LaunchedEffect(questionArchiveTaskId) {
        questionArchiveTaskId?.let(questionArchiveViewModel::open)
    }
    if (questionArchiveTaskId != null) {
        ZhihuQuestionArchiveScreen(
            viewModel = questionArchiveViewModel,
            onBack = { questionArchiveTaskId = null },
            onOpenChildTask = { childTaskId -> managedTaskId = childTaskId },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (destination == 1 && taskSelectionMode) {
                                "已选择 ${selectedTaskIds.size} 项"
                            } else {
                                destinations[destination].label
                            },
                        )
                    },
                    navigationIcon = {
                        if (destination == 1 && taskSelectionMode) {
                            IconButton(onClick = {
                                taskSelectionMode = false
                                selectedTaskIds = emptySet()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "退出多选")
                            }
                        }
                    },
                    actions = {
                        if (destination == 1 && taskSection == 0 &&
                            creatorState.selectedCreator == null
                        ) {
                            if (taskSelectionMode) {
                                IconButton(onClick = {
                                    selectedTaskIds = if (selectedTaskIds == selectableTaskIds) {
                                        emptySet()
                                    } else {
                                        selectableTaskIds
                                    }
                                }) {
                                    Icon(Icons.Default.SelectAll, contentDescription = "全选任务")
                                }
                                IconButton(
                                    onClick = { showBatchRedownloadDialog = true },
                                    enabled = selectedRedownloadTasks.isNotEmpty(),
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "重新下载所选任务")
                                }
                                IconButton(
                                    onClick = { showBatchDeleteDialog = true },
                                    enabled = selectedTaskIds.isNotEmpty(),
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除所选任务")
                                }
                            } else if (selectableTaskIds.isNotEmpty()) {
                                IconButton(onClick = { taskSelectionMode = true }) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "批量删除任务")
                                }
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = destination == index,
                        onClick = {
                            if (index == 1) {
                                openIndependentTasks(uiState.tasks.firstOrNull()?.id)
                            } else {
                                destination = index
                                taskSelectionMode = false
                                selectedTaskIds = emptySet()
                            }
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            when (destination) {
                0 -> HomeScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    creatorState = creatorState,
                    creatorViewModel = creatorViewModel,
                    onShowTasks = openIndependentTasks,
                    onShowCreators = {
                        taskSection = 1
                        taskSelectionMode = false
                        selectedTaskIds = emptySet()
                        destination = 1
                    },
                    onOpenLoginEnvironment = openLoginEnvironment,
                )
                1 -> TaskHubScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    creatorState = creatorState,
                    creatorViewModel = creatorViewModel,
                    selectedSection = taskSection,
                    onSelectedSection = { section ->
                        taskSection = section
                        if (section != 0) {
                            taskSelectionMode = false
                            selectedTaskIds = emptySet()
                        }
                    },
                    chooseFolder = { folderPicker.launch(null) },
                    requestAllFilesAccess = requestAllFilesAccess,
                    onManageTask = { taskId -> managedTaskId = taskId },
                    selectionMode = taskSelectionMode,
                    selectedTaskIds = selectedTaskIds,
                    taskPlatformFilter = taskPlatformFilter,
                    onTaskPlatformFilter = { platform ->
                        taskPlatformFilterWire = platform?.wireValue.orEmpty()
                    },
                    onToggleTaskSelection = { taskId ->
                        selectedTaskIds = toggleTaskSelection(selectedTaskIds, taskId)
                    },
                    focusedTaskId = focusedTaskId,
                    onTaskFocused = { focusedTaskId = null },
                    onOpenQuestionArchive = { taskId ->
                        questionArchiveTaskId = taskId
                    },
                )
                2 -> DiagnosticsScreen(
                    logText = uiState.logText,
                    isExporting = uiState.isExportingDiagnostics,
                    viewModel = viewModel,
                )
                else -> SettingsScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    chooseFolder = { folderPicker.launch(null) },
                    requestAllFilesAccess = requestAllFilesAccess,
                )
            }
        }
    }
    if (showBatchRedownloadDialog) {
        val skippedCount = selectedTasks.size - selectedRedownloadTasks.size
        AlertDialog(
            onDismissRequest = { showBatchRedownloadDialog = false },
            title = { Text("重新下载所选任务") },
            text = {
                Column {
                    Text(
                        "将重新解析并下载 ${selectedRedownloadTasks.size} 个任务，删除这些任务已登记的" +
                            "现有下载文件，并创建新的任务文件夹。",
                    )
                    if (skippedCount > 0) {
                        Text("另有 $skippedCount 个任务正在运行或状态不允许，将自动跳过。")
                    }
                    Text("任务文件夹中的其他文件会保留。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedRedownloadTasks.any(viewModel::requiresAllFilesAccess)) {
                            requestAllFilesAccess()
                            viewModel.showMessage("授权后请再次点击批量重新下载")
                            showBatchRedownloadDialog = false
                        } else {
                            viewModel.retryTasks(selectedTasks)
                            showBatchRedownloadDialog = false
                            taskSelectionMode = false
                            selectedTaskIds = emptySet()
                        }
                    },
                    enabled = selectedRedownloadTasks.isNotEmpty(),
                ) { Text("重新下载") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRedownloadDialog = false }) { Text("取消") }
            },
        )
    }
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showBatchDeleteDialog = false
                batchDeleteFiles = false
            },
            title = { Text("删除所选任务") },
            text = {
                Column {
                    Text("确定删除所选的 ${selectedTasks.size} 个任务吗？")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = batchDeleteFiles,
                            onCheckedChange = { batchDeleteFiles = it },
                        )
                        Text("同时删除下载内容和空任务文件夹")
                    }
                    Text("任务文件夹中的其他文件不会被删除。")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (batchDeleteFiles && selectedTasks.any(viewModel::requiresAllFilesAccess)) {
                            requestAllFilesAccess()
                        } else {
                            viewModel.deleteTasks(selectedTasks, batchDeleteFiles)
                            showBatchDeleteDialog = false
                            batchDeleteFiles = false
                            taskSelectionMode = false
                            selectedTaskIds = emptySet()
                        }
                    },
                    enabled = selectedTasks.isNotEmpty(),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatchDeleteDialog = false
                    batchDeleteFiles = false
                }) { Text("取消") }
            },
        )
    }
}
