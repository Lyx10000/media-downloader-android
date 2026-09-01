package com.local.douyindownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            DownloaderTheme {
                DownloaderApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForeground()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                viewModel.setIncomingText(it)
                viewModel.beginParse()
            }
        }
    }
}

private data class Destination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloaderApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var destination by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(viewModel.message) {
        if (viewModel.message.isNotBlank()) snackbar.showSnackbar(viewModel.consumeMessage())
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
                .imePadding(),
        ) {
            when (destination) {
                0 -> HomeScreen(viewModel, onShowTasks = { destination = 1 })
                1 -> TasksScreen(
                    viewModel = viewModel,
                    chooseFolder = { folderPicker.launch(null) },
                    requestAllFilesAccess = requestAllFilesAccess,
                )
                2 -> DiagnosticsScreen(viewModel)
                else -> SettingsScreen(
                    viewModel = viewModel,
                    chooseFolder = { folderPicker.launch(null) },
                    requestAllFilesAccess = requestAllFilesAccess,
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(viewModel: MainViewModel, onShowTasks: () -> Unit) {
    val context = LocalContext.current
    when (val state = viewModel.parseState) {
        ParseUiState.Idle -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "原画质下载",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "粘贴分享文本，或在抖音中直接分享到本应用。解析、下载和媒体处理全部在本机完成。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = viewModel.inputText,
                    onValueChange = viewModel::setIncomingText,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("抖音分享文本或链接") },
                    minLines = 4,
                    supportingText = { Text("剪贴板只会在你点击“粘贴”后读取") },
                    trailingIcon = {
                        if (viewModel.inputText.isNotEmpty()) {
                            IconButton(onClick = viewModel::clearInput) {
                                Icon(Icons.Default.Clear, contentDescription = "清空输入内容")
                            }
                        }
                    },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            ?.let(viewModel::setIncomingText)
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("粘贴")
                    }
                    Button(onClick = viewModel::beginParse, enabled = viewModel.inputText.isNotBlank()) {
                        Text("解析作品")
                    }
                }
            }
            if (viewModel.tasks.isNotEmpty()) {
                item {
                    OutlinedCard(onClick = onShowTasks, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("最近任务", style = MaterialTheme.typography.titleMedium)
                            Text(
                                viewModel.tasks.first().stage,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        is ParseUiState.LoadingWeb -> WebEnvironment(
            url = state.url,
            title = "正在建立抖音解析环境",
            onReady = viewModel::parseWithCookies,
            onCancel = viewModel::resetParse,
        )

        ParseUiState.Parsing -> ParsingStatus("正在读取完整质量档位……")
        is ParseUiState.Ready -> ResultScreen(
            result = state.result,
            selectedVariant = viewModel.selectedVariant,
            selectedMode = viewModel.selectedMode,
            onVariant = viewModel::selectVariant,
            onMode = viewModel::selectMode,
            onDownload = { viewModel.queueDownload(state.result); onShowTasks() },
            onBack = viewModel::resetParse,
        )
        is ParseUiState.Error -> ErrorScreen(state, viewModel::retryParse, viewModel::resetParse)
    }
}

@Composable
private fun ParsingStatus(text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text(text)
    }
}

@Composable
private fun WebEnvironment(
    url: String,
    title: String,
    onReady: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        DouyinWebView(
            url = url,
            onReady = onReady,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0f),
        )
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(
                    "正在后台准备解析环境，请稍候……",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

@Composable
private fun DouyinWebView(
    url: String,
    onReady: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    autoContinue: Boolean = true,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                val currentWebView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = DESKTOP_USER_AGENT
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(currentWebView, true)
                }
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    private var delivered = false
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        if (autoContinue && !delivered && finishedUrl.contains("douyin.com")) {
                            view.postDelayed({
                                if (!delivered) {
                                    delivered = true
                                    CookieManager.getInstance().flush()
                                    onReady(
                                        CookieManager.getInstance().getCookie("https://www.douyin.com/")
                                            .orEmpty(),
                                    )
                                }
                            }, 3_000)
                        }
                    }
                }
                loadUrl(url)
            }
        },
        update = { webView -> if (webView.url.isNullOrBlank()) webView.loadUrl(url) },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

@Composable
private fun ResultScreen(
    result: ParseResult,
    selectedVariant: Int,
    selectedMode: String,
    onVariant: (Int) -> Unit,
    onMode: (String) -> Unit,
    onDownload: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            if (result.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = result.coverUrl,
                    contentDescription = "作品封面",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
            }
            Text(result.author.ifBlank { "抖音作品" }, style = MaterialTheme.typography.titleLarge)
            if (result.description.isNotBlank()) {
                Text(
                    result.description,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (result.kind == "image") {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("原图 × ${result.imageUrls.size}", style = MaterialTheme.typography.titleMedium)
                        if (result.musicUrls.isNotEmpty()) Text("包含 BGM")
                    }
                }
            }
        } else {
            item { Text("选择清晰度", style = MaterialTheme.typography.titleMedium) }
            items(result.variants.size) { index ->
                val variant = result.variants[index]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selectedVariant == index) { onVariant(index) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedVariant == index, onClick = { onVariant(index) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(variant.label)
                        if (index == 0) Text("最高档", color = MaterialTheme.colorScheme.primary)
                        if (variant.codec.contains("265")) {
                            Text("H.265：旧播放器可能不兼容", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { Text("保存模式", style = MaterialTheme.typography.titleMedium) }
            items(videoModes(result.audioUrls.isEmpty())) { (mode, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selectedMode == mode) { onMode(mode) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedMode == mode, onClick = { onMode(mode) })
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text("返回") }
                Button(
                    onClick = onDownload,
                    enabled = result.kind == "image" || result.variants.isNotEmpty(),
                ) { Text(if (result.kind == "image") "下载原图" else "开始下载") }
            }
        }
    }
}

@Composable
private fun ErrorScreen(state: ParseUiState.Error, retry: () -> Unit, back: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("解析失败", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Text("${state.code}：${state.message}")
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = back) { Text("返回") }
            Button(onClick = retry) { Icon(Icons.Default.Refresh, null); Text("重试") }
        }
    }
}

@Composable
private fun CenterStatus(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksScreen(
    viewModel: MainViewModel,
    chooseFolder: () -> Unit,
    requestAllFilesAccess: () -> Unit,
) {
    val context = LocalContext.current
    var shareFiles by remember { mutableStateOf(emptyList<ShareableFile>()) }
    var pendingDelete by remember { mutableStateOf<TaskRecord?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }
    var recoveryTask by remember { mutableStateOf<TaskRecord?>(null) }
    LaunchedEffect(Unit) { viewModel.onTasksVisible() }
    if (viewModel.tasks.isEmpty()) {
        CenterStatus("还没有下载任务")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(viewModel.tasks, key = TaskRecord::id) { task ->
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
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (task.fileState == FileState.MISSING) 0.62f else 1f)
                    .then(
                        if (recoverable) Modifier.clickable { recoveryTask = task }
                        else Modifier,
                    ),
                colors = CardDefaults.outlinedCardColors(containerColor = containerColor),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { pendingDelete = task; deleteFiles = false }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除任务")
                        }
                    }
                    Text(
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(task.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when (task.fileState) {
                            FileState.PARTIAL -> "部分文件已删除"
                            FileState.MISSING -> "文件已被删除"
                            FileState.STORAGE_UNAVAILABLE -> "保存目录已失效"
                            FileState.DELETE_FAILED -> "部分内容删除失败"
                            else -> task.stage
                        },
                    )
                    if (task.status in setOf("QUEUED", "RUNNING", "DELETING")) {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = { viewModel.cancelTask(task) }) { Text("取消") }
                    }
                    if (task.error.isNotBlank()) {
                        Text(task.error, color = MaterialTheme.colorScheme.error)
                    }
                    if (task.status == "FAILED" && task.fileState != FileState.DELETE_FAILED) {
                        Button(onClick = { viewModel.retryTask(task) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("重试")
                        }
                    }
                    if (task.outputUris.isNotEmpty() && task.fileState in setOf(
                            FileState.AVAILABLE,
                            FileState.PARTIAL,
                            FileState.UNKNOWN,
                        )
                    ) {
                        Button(onClick = {
                            shareFiles = resolveShareableFiles(
                                context.contentResolver,
                                task.outputUris,
                            )
                        }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("分享文件")
                        }
                    }
                }
            }
        }
    }
    if (shareFiles.isNotEmpty()) {
        ShareFilesSheet(
            files = shareFiles,
            onDismiss = { shareFiles = emptyList() },
            onShare = { selected ->
                buildFileShareIntent(context.contentResolver, selected)?.let { intent ->
                    shareFiles = emptyList()
                    context.startActivity(Intent.createChooser(intent, "分享下载文件"))
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareFilesSheet(
    files: List<ShareableFile>,
    onDismiss: () -> Unit,
    onShare: (List<ShareableFile>) -> Unit,
) {
    var selectedUris by remember(files) { mutableStateOf(emptySet<String>()) }
    val selectedFiles = files.filter { it.uri.toString() in selectedUris }
    val selectedCategory = selectedFiles.firstOrNull()?.category
    val imageUris = files.filter { it.category == "image" }.map { it.uri.toString() }.toSet()

    fun toggle(file: ShareableFile) {
        val key = file.uri.toString()
        selectedUris = if (key in selectedUris) {
            selectedUris - key
        } else if (selectedCategory == null || selectedCategory == file.category) {
            selectedUris + key
        } else {
            selectedUris
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("选择要分享的文件", style = MaterialTheme.typography.titleLarge)
            Text(
                "可以多选同一类媒体；图片、视频和音频不能混合分享。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { selectedUris = imageUris },
                    enabled = imageUris.isNotEmpty(),
                ) { Text("全选图片") }
                TextButton(
                    onClick = { selectedUris = emptySet() },
                    enabled = selectedUris.isNotEmpty(),
                ) { Text("清空选择") }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(files, key = { it.uri.toString() }) { file ->
                    val checked = file.uri.toString() in selectedUris
                    val enabled = checked || selectedCategory == null || selectedCategory == file.category
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { toggle(file) },
                        headlineContent = {
                            Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text("${mediaCategoryLabel(file.category)} · ${file.mimeType}")
                        },
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle(file) },
                                enabled = enabled,
                            )
                        },
                    )
                }
            }
            Button(
                onClick = { onShare(selectedFiles) },
                enabled = selectedFiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (selectedFiles.isEmpty()) "选择文件" else "分享 ${selectedFiles.size} 个文件")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun mediaCategoryLabel(category: String): String = when (category) {
    "image" -> "图片"
    "video" -> "视频"
    "audio" -> "音频"
    else -> "文件"
}

@Composable
private fun DiagnosticsScreen(viewModel: MainViewModel) {
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
                    viewModel.logText.ifBlank { "暂无日志" },
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
private fun SettingsScreen(
    viewModel: MainViewModel,
    chooseFolder: () -> Unit,
    requestAllFilesAccess: () -> Unit,
) {
    var showWebView by remember { mutableStateOf(false) }
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
                    Switch(checked = viewModel.preferH264, onCheckedChange = viewModel::updatePreferH264)
                },
            )
        }
        item { Text("默认保存模式", style = MaterialTheme.typography.titleMedium) }
        items(videoModes(false)) { (mode, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(viewModel.selectedMode == mode) { viewModel.setDefaultMode(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = viewModel.selectedMode == mode,
                    onClick = { viewModel.setDefaultMode(mode) },
                )
                Text(label)
            }
        }
        item { HorizontalDivider() }
        item { Text("保存位置", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                viewModel.customTreeUri ?: "内部存储/Download/DouyinDownloader/",
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
                if (viewModel.customTreeUri != null) {
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
            Button(onClick = { showWebView = true }) {
                Text("登录或刷新抖音环境")
            }
        }
        item { HorizontalDivider() }
        item {
            Text("版本", style = MaterialTheme.typography.titleMedium)
            Text("应用 ${BuildConfig.VERSION_NAME} · 解析器 android-core-3")
            Text("完全本地运行，不使用服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showWebView) {
        FullScreenWebEnvironment(onDismiss = { showWebView = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenWebEnvironment(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = { Text("抖音登录环境") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Clear, contentDescription = "关闭登录环境")
                            }
                        },
                    )
                },
            ) { innerPadding ->
                DouyinWebView(
                    url = "https://www.douyin.com/",
                    onReady = {},
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding(),
                    autoContinue = false,
                )
            }
        }
    }
}

private fun videoModes(muxed: Boolean): List<Pair<String, String>> = if (muxed) {
    listOf(
        "merge_keep" to "原始音视频 + 视频分轨 + 音频分轨",
        "tracks" to "视频分轨 + 音频分轨",
        "video_only" to "仅视频轨",
        "audio_only" to "仅音频轨",
    )
} else {
    listOf(
        "merge_keep" to "合成文件 + 视频轨 + 音频轨",
        "tracks" to "视频轨 + 音频轨，不合成",
        "video_only" to "仅视频轨",
        "audio_only" to "仅音频轨",
    )
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
