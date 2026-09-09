package com.local.multiplatformdownloader.feature.home

import com.local.multiplatformdownloader.core.model.DocumentAssetKind
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaAttachment
import com.local.multiplatformdownloader.core.model.MediaAttachmentKind
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebNavigationTarget
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.model.classifyWebNavigation
import com.local.multiplatformdownloader.core.model.isPlatformPage
import com.local.multiplatformdownloader.core.model.upgradePlatformCleartextUrl
import com.local.multiplatformdownloader.feature.creator.CREATOR_BATCH_PLATFORMS
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryUiState
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryViewModel
import com.local.multiplatformdownloader.feature.creator.HomeInputMode
import com.local.multiplatformdownloader.feature.creator.creatorKey
import com.local.multiplatformdownloader.feature.creator.taskCreatorKey
import com.local.multiplatformdownloader.feature.tasks.isTaskQueueVisible
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionDownloadScope
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
import com.local.multiplatformdownloader.platform.instagram.INSTAGRAM_SNAPSHOT_SCRIPT

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
internal fun XiaohongshuCredentialProbe(onResult: (WebPageSnapshot?) -> Unit) {
    var completed by remember { mutableStateOf(false) }
    fun finish(snapshot: WebPageSnapshot?) {
        if (completed) return
        completed = true
        onResult(snapshot)
    }
    LaunchedEffect(Unit) {
        delay(XHS_CREDENTIAL_PROBE_TIMEOUT_MS)
        finish(null)
    }
    PlatformWebView(
        platform = SourcePlatform.XIAOHONGSHU,
        url = SourcePlatform.XIAOHONGSHU.loginUrl,
        onReady = { _, snapshot -> finish(snapshot) },
        modifier = Modifier.size(1.dp).alpha(0f),
        capturePage = true,
        snapshotDelayMs = XHS_CREDENTIAL_PROBE_DELAY_MS,
        snapshotScript = XHS_CREDENTIAL_SNAPSHOT_SCRIPT,
    )
}

@Composable
internal fun WebEnvironment(
    platform: SourcePlatform,
    url: String,
    title: String,
    capturePage: Boolean,
    snapshotDelayMs: Long = WEB_PAGE_SNAPSHOT_DELAY_MS,
    statusText: String = "正在后台准备解析环境，请稍候……",
    cancelLabel: String = "取消",
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
            snapshotScript = if (platform == SourcePlatform.INSTAGRAM) INSTAGRAM_SNAPSHOT_SCRIPT else PAGE_SNAPSHOT_SCRIPT,
            desktopMode = platform != SourcePlatform.INSTAGRAM,
            snapshotDelayMs = snapshotDelayMs,
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
                    statusText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedButton(onClick = onCancel) { Text(cancelLabel) }
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
    snapshotDelayMs: Long = WEB_PAGE_SNAPSHOT_DELAY_MS,
    snapshotScript: String = PAGE_SNAPSHOT_SCRIPT,
    desktopMode: Boolean = true,
    assistLoginViewport: Boolean = false,
    onPageFinishedEvent: (String) -> Unit = {},
    onLoginAssistResult: (String, String) -> Unit = { _, _ -> },
    onMainFrameError: (String, Int, String) -> Unit = { _, _, _ -> },
    onExternalNavigationFailed: (String) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EnvironmentWebView(context).apply {
                if (platform == SourcePlatform.INSTAGRAM) {
                    // Compose's ViewGroup otherwise supplies WRAP_CONTENT defaults. Chromium
                    // uses that height parameter to enable forceZeroLayoutHeight even when
                    // Compose measures the native view to the full screen size (100vh = 0).
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                val currentWebView = this
                val diagnoseInstagramLogin = assistLoginViewport && platform == SourcePlatform.INSTAGRAM
                var scriptErrors = 0
                val resourceFailures = linkedSetOf<String>()
                fun recordResourceFailure(resourceUrl: String, code: Int) {
                    if (!diagnoseInstagramLogin || resourceFailures.size >= 5) return
                    val host = android.net.Uri.parse(resourceUrl).host.orEmpty()
                    resourceFailures += "$host:$code"
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (assistLoginViewport && shouldBypassLoginCache(platform)) {
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    clearCache(true)
                }
                if (desktopMode) {
                    settings.userAgentString = DESKTOP_USER_AGENT
                } else if (platform in setOf(SourcePlatform.X, SourcePlatform.INSTAGRAM)) {
                    // Avoid the embedded-WebView marker that can make X hide its normal login flow.
                    settings.userAgentString = MOBILE_CHROME_USER_AGENT
                }
                settings.useWideViewPort = desktopMode || platform == SourcePlatform.INSTAGRAM
                settings.loadWithOverviewMode = desktopMode
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                isFocusable = true
                isFocusableInTouchMode = true
                isNestedScrollingEnabled = true
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(currentWebView, true)
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (diagnoseInstagramLogin && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            // Count errors without logging console text, which may contain credentials.
                            scriptErrors += 1
                        }
                        return super.onConsoleMessage(message)
                    }
                }
                webViewClient = object : WebViewClient() {
                    private var delivered = false
                    private var assistGeneration = 0L

                    private fun deliver(source: CookieReadySource, delayMs: Long = 0) {
                        if (delivered) return
                        delivered = true
                        currentWebView.postDelayed({
                            if (capturePage) {
                                currentWebView.evaluateJavascript(snapshotScript) { value ->
                                    onReady(source, WebPageSnapshot.fromJavascriptResult(value))
                                }
                            } else {
                                onReady(source, null)
                            }
                        }, delayMs)
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        super.onPageFinished(view, finishedUrl)
                        if (shouldFlushLoginCookiesOnPageFinished(platform)) {
                            CookieManager.getInstance().flush()
                        }
                        if (assistLoginViewport) {
                            assistCallbacks.forEach { view.removeCallbacks(it) }
                            assistCallbacks.clear()
                            assistGeneration += 1L
                            val generation = assistGeneration
                            loginAssistDelays(platform).forEach { delayMs ->
                                val assist = Runnable {
                                    if (generation != assistGeneration) return@Runnable
                                    view.evaluateJavascript(loginViewportScript(platform)) { result ->
                                        val diagnostics = if (diagnoseInstagramLogin) {
                                            val visibleRect = android.graphics.Rect()
                                            val globallyVisible = view.getGlobalVisibleRect(visibleRect)
                                            "|js_errors=$scriptErrors|resource_failures=${resourceFailures.joinToString(",")}" +
                                                "|native_size=${view.width}:${view.height}" +
                                                "|native_layout=${view.layoutParams?.width}:${view.layoutParams?.height}" +
                                                "|native_visible=$globallyVisible:${view.isShown}:${view.alpha}" +
                                                "|native_visible_size=${visibleRect.width()}:${visibleRect.height()}" +
                                                "|native_render=${view.isHardwareAccelerated}:${view.layerType}" +
                                                "|webview=${WebView.getCurrentWebViewPackage()?.versionName.orEmpty()}"
                                        } else ""
                                        onLoginAssistResult(
                                            view.url.orEmpty().ifBlank { finishedUrl },
                                            result.orEmpty().trim().trim('"') + diagnostics + "|pass_ms=$delayMs",
                                        )
                                    }
                                }
                                assistCallbacks += assist
                                view.postDelayed(assist, delayMs)
                            }
                        }
                        onPageFinishedEvent(finishedUrl)
                        if (autoContinue && isPlatformPage(finishedUrl, platform)) {
                            deliver(
                                CookieReadySource.PAGE_READY,
                                if (capturePage) snapshotDelayMs else WEB_COOKIE_SETTLE_DELAY_MS,
                            )
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val requestedUrl = request.url.toString()
                        val secureUrl = upgradePlatformCleartextUrl(requestedUrl, platform)
                        if (secureUrl != null) {
                            view.loadUrl(secureUrl)
                            return true
                        }
                        return when (classifyWebNavigation(requestedUrl)) {
                            WebNavigationTarget.WEB -> false
                            WebNavigationTarget.BLOCKED -> {
                                onExternalNavigationFailed("已阻止不安全的链接")
                                true
                            }
                            WebNavigationTarget.EXTERNAL_APP -> {
                                val launched = runCatching {
                                    val intent = if (request.url.scheme == "intent") {
                                        Intent.parseUri(requestedUrl, Intent.URI_INTENT_SCHEME)
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
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        super.onReceivedError(view, request, error)
                        recordResourceFailure(request.url.toString(), error.errorCode)
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
                        recordResourceFailure(request.url.toString(), errorResponse.statusCode)
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenWebEnvironment(
    platform: SourcePlatform,
    credentialState: PlatformCredentialState,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onPageFinished: (String) -> Unit,
    onLoginAssistResult: (String, String) -> Unit,
    onPageError: (String, Int, String) -> Unit,
    onExternalNavigationFailed: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val instagram = platform == SourcePlatform.INSTAGRAM
    val startUrl = remember(platform) { loginEnvironmentStartUrl(platform, credentialState) }
    var reloadGeneration by remember(platform) { mutableStateOf(0) }
    var pageRendered by remember(platform, reloadGeneration) { mutableStateOf(false) }
    var pageFailed by remember(platform, reloadGeneration) { mutableStateOf(false) }
    var slowLoading by remember(platform, reloadGeneration) { mutableStateOf(false) }
    LaunchedEffect(platform, reloadGeneration) {
        if (instagram) {
            delay(20_000L)
            slowLoading = true
        }
    }
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
        Column(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
            if (platform == SourcePlatform.BILIBILI) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("完成官方网页登录后返回即可刷新状态。登录不保证取得高清或受限内容。",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { reloadGeneration += 1 }) { Text("重新加载") }
                }
            }
            if (instagram && !pageRendered) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        when {
                            pageFailed -> "登录网页加载失败，请检查网络后重新加载。"
                            slowLoading -> "登录内容尚未显示，可继续等待，或检查网络后重新加载。"
                            else -> "正在加载 Instagram 登录页面…"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (pageFailed || slowLoading) {
                        OutlinedButton(onClick = { reloadGeneration += 1 }) { Text("重新加载") }
                    }
                }
                if (!pageFailed && !slowLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            key(platform, reloadGeneration) {
                PlatformWebView(
                    platform = platform,
                    url = startUrl,
                    onReady = { _, _ -> },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    autoContinue = false,
                    desktopMode = shouldUseDesktopLoginMode(platform),
                    assistLoginViewport = shouldAssistLoginViewport(platform),
                    onPageFinishedEvent = onPageFinished,
                    onLoginAssistResult = { url, result ->
                        if (instagram) {
                            pageRendered = result.contains("|state=form-rendered|") ||
                                result.contains("|state=page-content-rendered|")
                        }
                        onLoginAssistResult(url, result)
                    },
                    onMainFrameError = { url, code, description ->
                        if (instagram) pageFailed = true
                        onPageError(url, code, description)
                    },
                    onExternalNavigationFailed = onExternalNavigationFailed,
                )
            }
        }
    }
}

internal fun videoModes(muxed: Boolean): List<Pair<DownloadMode, String>> = if (muxed) {
    listOf(
        DownloadMode.MERGE_KEEP to "原始音视频 + 视频分轨 + 音频分轨",
        DownloadMode.MP4_ONLY to "仅保留成品 MP4（含声音）",
        DownloadMode.TRACKS to "视频分轨 + 音频分轨",
        DownloadMode.VIDEO_ONLY to "仅视频轨",
        DownloadMode.AUDIO_ONLY to "仅音频轨",
    )
} else {
    listOf(
        DownloadMode.MERGE_KEEP to "合成文件 + 视频轨 + 音频轨",
        DownloadMode.MP4_ONLY to "仅保留合成 MP4",
        DownloadMode.TRACKS to "视频轨 + 音频轨，不合成",
        DownloadMode.VIDEO_ONLY to "仅视频轨",
        DownloadMode.AUDIO_ONLY to "仅音频轨",
    )
}

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"

private const val MOBILE_CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

private class EnvironmentWebView(context: Context) : WebView(context) {
    val assistCallbacks = mutableListOf<Runnable>()

    override fun destroy() {
        assistCallbacks.forEach { removeCallbacks(it) }
        assistCallbacks.clear()
        super.destroy()
    }
}

private const val WEB_ENVIRONMENT_TIMEOUT_MS = 15_000L
private const val WEB_COOKIE_SETTLE_DELAY_MS = 1_500L
internal const val WEB_PAGE_SNAPSHOT_DELAY_MS = 2_500L
internal const val CREATOR_SEARCH_SNAPSHOT_DELAY_MS = 4_500L
internal const val CREATOR_BATCH_SNAPSHOT_DELAY_MS = 4_500L
private const val XHS_CREDENTIAL_PROBE_DELAY_MS = 2_000L
private const val XHS_CREDENTIAL_PROBE_TIMEOUT_MS = 12_000L
internal const val XHS_WORK_SNAPSHOT_DELAY_MS = 4_500L
