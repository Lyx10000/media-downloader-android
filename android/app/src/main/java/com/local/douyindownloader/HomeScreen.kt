package com.local.douyindownloader

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.net.URI
import kotlinx.coroutines.delay

@Composable
internal fun HomeScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onShowTasks: () -> Unit,
) {
    val context = LocalContext.current
    when (val state = uiState.parseState) {
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
                    value = uiState.inputText,
                    onValueChange = viewModel::setIncomingText,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("抖音分享文本或链接") },
                    minLines = 4,
                    supportingText = { Text("剪贴板只会在你点击“粘贴”后读取") },
                    trailingIcon = {
                        if (uiState.inputText.isNotEmpty()) {
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
                    Button(onClick = viewModel::beginParse, enabled = uiState.inputText.isNotBlank()) {
                        Text("解析作品")
                    }
                }
            }
            if (uiState.tasks.isNotEmpty()) {
                item {
                    OutlinedCard(onClick = onShowTasks, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("最近任务", style = MaterialTheme.typography.titleMedium)
                            Text(
                                uiState.tasks.first().stage,
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
            selectedVariant = uiState.selectedVariant,
            selectedMode = uiState.selectedMode,
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
    onReady: (String, CookieReadySource) -> Unit,
    onCancel: () -> Unit,
) {
    var completed by remember(url) { mutableStateOf(false) }
    fun finish(source: CookieReadySource) {
        if (completed) return
        completed = true
        CookieManager.getInstance().flush()
        onReady(
            CookieManager.getInstance().getCookie(MainViewModel.DOUYIN_HOME_URL).orEmpty(),
            source,
        )
    }
    LaunchedEffect(url) {
        delay(WEB_ENVIRONMENT_TIMEOUT_MS)
        finish(CookieReadySource.TIMEOUT)
    }
    Box(Modifier.fillMaxSize()) {
        DouyinWebView(
            url = url,
            onReady = ::finish,
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
@SuppressLint("SetJavaScriptEnabled")
private fun DouyinWebView(
    url: String,
    onReady: (CookieReadySource) -> Unit,
    modifier: Modifier = Modifier,
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

                    private fun deliver(source: CookieReadySource, delayMs: Long = 0) {
                        if (delivered) return
                        delivered = true
                        currentWebView.postDelayed({ onReady(source) }, delayMs)
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        if (autoContinue && isDouyinPage(finishedUrl)) {
                            deliver(CookieReadySource.PAGE_READY, WEB_COOKIE_SETTLE_DELAY_MS)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (autoContinue && request.isForMainFrame) {
                            deliver(CookieReadySource.PAGE_ERROR)
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
    selectedMode: DownloadMode,
    onVariant: (Int) -> Unit,
    onMode: (DownloadMode) -> Unit,
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
        if (result.kind == MediaKind.IMAGE) {
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
                    enabled = result.kind == MediaKind.IMAGE || result.variants.isNotEmpty(),
                ) { Text(if (result.kind == MediaKind.IMAGE) "下载原图" else "开始下载") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenWebEnvironment(onDismiss: () -> Unit) {
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
                    url = MainViewModel.DOUYIN_HOME_URL,
                    onReady = { _ -> },
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

internal fun videoModes(muxed: Boolean): List<Pair<DownloadMode, String>> = if (muxed) {
    listOf(
        DownloadMode.MERGE_KEEP to "原始音视频 + 视频分轨 + 音频分轨",
        DownloadMode.TRACKS to "视频分轨 + 音频分轨",
        DownloadMode.VIDEO_ONLY to "仅视频轨",
        DownloadMode.AUDIO_ONLY to "仅音频轨",
    )
} else {
    listOf(
        DownloadMode.MERGE_KEEP to "合成文件 + 视频轨 + 音频轨",
        DownloadMode.TRACKS to "视频轨 + 音频轨，不合成",
        DownloadMode.VIDEO_ONLY to "仅视频轨",
        DownloadMode.AUDIO_ONLY to "仅音频轨",
    )
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

private const val WEB_ENVIRONMENT_TIMEOUT_MS = 15_000L
private const val WEB_COOKIE_SETTLE_DELAY_MS = 1_500L

internal fun isDouyinPage(url: String): Boolean {
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    return host == "douyin.com" || host.endsWith(".douyin.com")
}
