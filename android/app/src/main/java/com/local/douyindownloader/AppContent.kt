package com.local.douyindownloader

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloaderApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var destination by remember { mutableIntStateOf(0) }
    var managedTaskId by remember { mutableStateOf<String?>(null) }
    var readerTaskId by remember { mutableStateOf<String?>(null) }
    var loginPlatform by rememberSaveable { mutableStateOf<SourcePlatform?>(null) }
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

    val openLoginEnvironment: (SourcePlatform) -> Unit = { platform ->
        viewModel.onLoginEnvironmentOpened(platform)
        loginPlatform = platform
    }
    val activeLoginPlatform = loginPlatform
    if (activeLoginPlatform != null) {
        FullScreenWebEnvironment(
            platform = activeLoginPlatform,
            onDismiss = {
                viewModel.onLoginEnvironmentClosed(activeLoginPlatform)
                loginPlatform = null
            },
            onPageFinished = { url ->
                viewModel.onLoginPageFinished(activeLoginPlatform, url)
            },
            onPageError = { url, code, description ->
                viewModel.onLoginPageError(activeLoginPlatform, url, code, description)
            },
        )
        return
    }

    val managedTask = managedTaskId?.let { taskId ->
        uiState.tasks.firstOrNull { it.id == taskId }
    }
    val readerTask = readerTaskId?.let { taskId ->
        uiState.tasks.firstOrNull { it.id == taskId }
    }
    if (readerTaskId != null && readerTask == null) {
        LaunchedEffect(readerTaskId) { readerTaskId = null }
    }
    if (readerTask != null) {
        DocumentReaderScreen(
            task = readerTask,
            viewModel = viewModel,
            onBack = { readerTaskId = null },
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
            onOpenDocument = { readerTaskId = managedTask.id },
            onBack = { managedTaskId = null },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text(destinations[destination].label) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                destinations.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = destination == index,
                        onClick = { destination = index },
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
                    onShowTasks = { destination = 1 },
                    onOpenLoginEnvironment = openLoginEnvironment,
                )
                1 -> TasksScreen(
                    tasks = uiState.tasks,
                    viewModel = viewModel,
                    chooseFolder = { folderPicker.launch(null) },
                    requestAllFilesAccess = requestAllFilesAccess,
                    onManageTask = { taskId -> managedTaskId = taskId },
                )
                2 -> DiagnosticsScreen(
                    logText = uiState.logText,
                    viewModel = viewModel,
                )
                else -> SettingsScreen(
                    uiState = uiState,
                    viewModel = viewModel,
                    chooseFolder = { folderPicker.launch(null) },
                    requestAllFilesAccess = requestAllFilesAccess,
                    onOpenLoginEnvironment = openLoginEnvironment,
                )
            }
        }
    }
}
