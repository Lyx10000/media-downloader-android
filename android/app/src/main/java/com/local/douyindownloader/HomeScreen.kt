package com.local.douyindownloader

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
internal fun HomeScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onShowTasks: () -> Unit,
    onOpenLoginEnvironment: (SourcePlatform) -> Unit,
) {
    val context = LocalContext.current
    when (val state = uiState.parseState) {
        ParseUiState.Idle -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    "聚合下载",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "粘贴抖音、小红书或知乎分享文本，也可以从对应应用直接分享到这里。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = uiState.inputText,
                    onValueChange = viewModel::setIncomingText,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("抖音、小红书或知乎分享文本/链接") },
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
            item {
                PlatformCredentialCard(
                    states = uiState.platformCredentialStates,
                    onOpenLoginEnvironment = onOpenLoginEnvironment,
                )
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
            platform = state.platform,
            url = state.url,
            title = "正在建立${state.platform.displayName}解析环境",
            capturePage = state.capturePage,
            onReady = viewModel::parseWithCookies,
            onCancel = viewModel::resetParse,
        )

        ParseUiState.Parsing -> ParsingStatus("正在读取原始媒体和完整质量档位……")
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
private fun PlatformCredentialCard(
    states: Map<SourcePlatform, PlatformCredentialState>,
    onOpenLoginEnvironment: (SourcePlatform) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("平台登录状态", style = MaterialTheme.typography.titleMedium)
            SourcePlatform.entries.forEachIndexed { index, platform ->
                if (index > 0) HorizontalDivider()
                val detected = states[platform] == PlatformCredentialState.DETECTED
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLoginEnvironment(platform) }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformBrandBadge(platform)
                    Text(
                        if (detected) "检测到登录凭据" else "未检测到登录",
                        color = if (detected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                "未登录时部分作品可能解析失败；登录凭据也可能过期或触发平台风控。点击平台可登录或刷新环境。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
    platform: SourcePlatform,
    url: String,
    title: String,
    capturePage: Boolean,
    onReady: (String, CookieReadySource, WebPageSnapshot?) -> Unit,
    onCancel: () -> Unit,
) {
    var completed by remember(url) { mutableStateOf(false) }
    fun finish(source: CookieReadySource, snapshot: WebPageSnapshot? = null) {
        if (completed) return
        completed = true
        CookieManager.getInstance().flush()
        onReady(
            CookieManager.getInstance().getCookie(platform.homeUrl).orEmpty(),
            source,
            snapshot,
        )
    }
    LaunchedEffect(url) {
        delay(WEB_ENVIRONMENT_TIMEOUT_MS)
        finish(CookieReadySource.TIMEOUT)
    }
    Box(Modifier.fillMaxSize()) {
        PlatformWebView(
            platform = platform,
            url = url,
            onReady = ::finish,
            capturePage = capturePage,
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
private fun PlatformWebView(
    platform: SourcePlatform,
    url: String,
    onReady: (CookieReadySource, WebPageSnapshot?) -> Unit,
    modifier: Modifier = Modifier,
    autoContinue: Boolean = true,
    capturePage: Boolean = false,
    desktopMode: Boolean = true,
    assistLoginViewport: Boolean = false,
    onPageFinishedEvent: (String) -> Unit = {},
    onMainFrameError: (String, Int, String) -> Unit = { _, _, _ -> },
    onExternalNavigationFailed: (String) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                val currentWebView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (desktopMode) settings.userAgentString = DESKTOP_USER_AGENT
                settings.useWideViewPort = desktopMode
                settings.loadWithOverviewMode = desktopMode
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                isFocusable = true
                isFocusableInTouchMode = true
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
                        currentWebView.postDelayed({
                            if (capturePage) {
                                currentWebView.evaluateJavascript(PAGE_SNAPSHOT_SCRIPT) { value ->
                                    onReady(source, WebPageSnapshot.fromJavascriptResult(value))
                                }
                            } else {
                                onReady(source, null)
                            }
                        }, delayMs)
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        if (assistLoginViewport) {
                            view.evaluateJavascript(DOUYIN_LOGIN_VIEWPORT_SCRIPT, null)
                        }
                        onPageFinishedEvent(finishedUrl)
                        if (autoContinue && isPlatformPage(finishedUrl, platform)) {
                            deliver(
                                CookieReadySource.PAGE_READY,
                                if (capturePage) WEB_PAGE_SNAPSHOT_DELAY_MS else WEB_COOKIE_SETTLE_DELAY_MS,
                            )
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = when (classifyWebNavigation(request.url.toString())) {
                        WebNavigationTarget.WEB -> false
                        WebNavigationTarget.BLOCKED -> {
                            onExternalNavigationFailed("已阻止不安全的链接")
                            true
                        }
                        WebNavigationTarget.EXTERNAL_APP -> {
                            val launched = runCatching {
                                val intent = if (request.url.scheme == "intent") {
                                    Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME)
                                } else {
                                    Intent(Intent.ACTION_VIEW, request.url)
                                }.apply {
                                    addCategory(Intent.CATEGORY_BROWSABLE)
                                    component = null
                                    selector = null
                                }
                                view.context.startActivity(intent)
                            }.isSuccess
                            if (!launched) onExternalNavigationFailed("未找到可处理该链接的应用")
                            true
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            onMainFrameError(
                                request.url.toString(),
                                error.errorCode,
                                error.description?.toString().orEmpty(),
                            )
                            if (autoContinue) deliver(CookieReadySource.PAGE_ERROR)
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request.isForMainFrame) {
                            onMainFrameError(
                                request.url.toString(),
                                errorResponse.statusCode,
                                errorResponse.reasonPhrase.orEmpty(),
                            )
                        }
                    }
                }
                loadUrl(url)
            }
        },
        update = { webView -> if (webView.url.isNullOrBlank()) webView.loadUrl(url) },
        onRelease = { webView ->
            webView.clearFocus()
            webView.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(webView.windowToken, 0)
            webView.onPause()
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
            Text(
                result.author.ifBlank { "${result.platform.displayName}作品" },
                style = MaterialTheme.typography.titleLarge,
            )
            if (result.description.isNotBlank()) {
                Text(
                    result.description,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (result.kind) {
            MediaKind.IMAGE -> {
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("原图 × ${result.imageUrls.size}", style = MaterialTheme.typography.titleMedium)
                            if (result.livePhotos.isNotEmpty()) {
                                Text("实况照片 × ${result.livePhotos.size}（保留图片和动态视频）")
                            }
                            if (result.musicUrls.isNotEmpty()) Text("包含 BGM")
                        }
                    }
                }
            }
            MediaKind.DOCUMENT -> {
                item {
                    val document = result.document
                    val images = document?.assets?.count { it.kind == DocumentAssetKind.IMAGE } ?: 0
                    val videos = document?.assets?.count { it.kind == DocumentAssetKind.VIDEO } ?: 0
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("知乎${document?.type?.displayLabel.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                            Text("正文将保存为 Markdown")
                            Text("图片 $images 张 · 内嵌视频 $videos 个")
                        }
                    }
                }
            }
            MediaKind.VIDEO -> {
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
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text("返回") }
                Button(
                    onClick = onDownload,
                    enabled = result.kind != MediaKind.VIDEO || result.variants.isNotEmpty(),
                ) {
                    Text(
                        when (result.kind) {
                            MediaKind.IMAGE -> if (result.livePhotos.isEmpty()) "下载原图" else "下载原图和实况"
                            MediaKind.DOCUMENT -> "下载完整内容"
                            MediaKind.VIDEO -> "开始下载"
                        },
                    )
                }
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
internal fun FullScreenWebEnvironment(
    platform: SourcePlatform,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onPageFinished: (String) -> Unit,
    onPageError: (String, Int, String) -> Unit,
    onExternalNavigationFailed: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("${platform.displayName}登录环境") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Clear, contentDescription = "关闭登录环境")
                    }
                },
            )
        },
    ) { innerPadding ->
        PlatformWebView(
            platform = platform,
            url = platform.loginUrl,
            onReady = { _, _ -> },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            autoContinue = false,
            desktopMode = false,
            assistLoginViewport = shouldAssistLoginViewport(platform),
            onPageFinishedEvent = onPageFinished,
            onMainFrameError = onPageError,
            onExternalNavigationFailed = onExternalNavigationFailed,
        )
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
private const val WEB_PAGE_SNAPSHOT_DELAY_MS = 2_500L

internal fun shouldAssistLoginViewport(platform: SourcePlatform): Boolean =
    platform == SourcePlatform.DOUYIN

internal const val DOUYIN_LOGIN_VIEWPORT_SCRIPT = """
    (function() {
      try {
        function visible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            rect.width > 120 && rect.height > 80;
        }

        function score(element) {
          if (!visible(element)) return -1;
          var value = 0;
          var role = (element.getAttribute('role') || '').toLowerCase();
          var className = String(element.className || '').toLowerCase();
          var text = (element.innerText || '').slice(0, 500);
          if (role === 'dialog') value += 80;
          if (className.indexOf('login') >= 0 || className.indexOf('passport') >= 0) value += 30;
          if (element.querySelector('input')) value += 100;
          if (element.querySelector('iframe')) value += 55;
          if (element.querySelector('canvas, img')) value += 15;
          if (text.indexOf('登录') >= 0 || text.indexOf('验证码') >= 0 || text.indexOf('扫码') >= 0) {
            value += 35;
          }
          return value;
        }

        function centerLogin() {
          var selectors = [
            '[role="dialog"]',
            '[class*="login"]',
            '[class*="Login"]',
            '[class*="passport"]',
            '[class*="Passport"]'
          ];
          var candidates = [];
          selectors.forEach(function(selector) {
            document.querySelectorAll(selector).forEach(function(element) {
              if (candidates.indexOf(element) < 0) candidates.push(element);
            });
          });
          var target = null;
          var best = 69;
          candidates.forEach(function(element) {
            var candidateScore = score(element);
            if (candidateScore > best) {
              best = candidateScore;
              target = element;
            }
          });
          if (!target) return false;
          target.scrollIntoView({block: 'center', inline: 'center', behavior: 'auto'});
          window.requestAnimationFrame(function() {
            var rect = target.getBoundingClientRect();
            window.scrollBy(
              rect.left + rect.width / 2 - window.innerWidth / 2,
              rect.top + rect.height / 2 - window.innerHeight / 2
            );
          });
          return true;
        }

        if (centerLogin()) return 'centered';
        var attempts = 0;
        var timer = window.setInterval(function() {
          attempts += 1;
          if (centerLogin() || attempts >= 16) window.clearInterval(timer);
        }, 500);
        return 'waiting';
      } catch (error) {
        return 'failed';
      }
    })();
"""

private const val PAGE_SNAPSHOT_SCRIPT = """
    (function() {
      var path = window.location.pathname || '';
      var answerMatch = path.match(/\/answer\/(\d+)/);
      var root = null;
      var content = null;
      if (answerMatch) {
        var answerId = answerMatch[1];
        root = document.querySelector('[data-answer-id="' + answerId + '"], #answer-' + answerId);
        if (!root) {
          var answers = Array.prototype.slice.call(document.querySelectorAll('.AnswerItem'));
          root = answers.find(function(item) {
            return (item.getAttribute('data-zop') || '').indexOf(answerId) >= 0 ||
              (item.getAttribute('name') || '') === answerId;
          }) || answers[0] || null;
        }
        content = root && root.querySelector('.RichContent-inner, .RichText');
      } else if (/\/pin\//.test(path)) {
        root = document.querySelector('.PinItem, .Pin-content, main');
        content = root && root.querySelector('.RichContent-inner, .RichText, .PinItem-content, .Pin-content');
      } else {
        root = document.querySelector('.Post-Main, article, main');
        content = document.querySelector('.Post-RichTextContainer .RichText, .Post-RichTextContainer, article .RichText');
      }
      var state = document.querySelector('script#js-initialData, script#__NEXT_DATA__');
      var initialData = state ? (state.textContent || '') : '';
      if (!initialData && window.__INITIAL_STATE__) {
        try { initialData = JSON.stringify(window.__INITIAL_STATE__); } catch (_) {}
      }
      var titleNode = document.querySelector('h1.Post-Title, .QuestionHeader-title, article h1, main h1, h1');
      var authorNode = root && root.querySelector('.AuthorInfo-name, [itemprop="name"], .UserLink-link');
      return JSON.stringify({
        finalUrl: window.location.href || '',
        initialData: initialData,
        title: titleNode ? (titleNode.textContent || '').trim() : (document.title || '').trim(),
        author: authorNode ? (authorNode.textContent || '').trim() : '',
        contentHtml: content ? (content.innerHTML || '') : '',
        visibleText: document.body ? (document.body.innerText || '').slice(0, 8000) : ''
      });
    })();
"""
