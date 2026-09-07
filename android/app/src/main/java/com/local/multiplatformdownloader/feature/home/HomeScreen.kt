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
internal fun HomeScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    creatorState: CreatorLibraryUiState,
    creatorViewModel: CreatorLibraryViewModel,
    onShowTasks: (String?) -> Unit,
    onShowCreators: () -> Unit,
    onOpenLoginEnvironment: (SourcePlatform) -> Unit,
) {
    val context = LocalContext.current
    if (
        uiState.parseState == ParseUiState.Idle &&
        uiState.platformCredentialStates[SourcePlatform.XIAOHONGSHU] ==
        PlatformCredentialState.CHECKING
    ) {
        XiaohongshuCredentialProbe(viewModel::onXiaohongshuCredentialProbe)
    }
    when (val state = uiState.parseState) {
        ParseUiState.Idle -> creatorState.webResolveRequest?.let { request ->
            WebEnvironment(
                platform = request.platform,
                url = request.url,
                title = "正在精确查找${request.platform.displayName}作者",
                capturePage = true,
                snapshotDelayMs = CREATOR_SEARCH_SNAPSHOT_DELAY_MS,
                onReady = creatorViewModel::completeWebResolve,
                onCancel = creatorViewModel::cancelWebResolve,
            )
        } ?: LazyColumn(
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
                    "解析单个作品，或者查找作者并批量选择公开作品。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = creatorState.homeInputMode == HomeInputMode.WORK,
                        onClick = { creatorViewModel.setHomeInputMode(HomeInputMode.WORK) },
                        label = { Text("作品解析") },
                    )
                    FilterChip(
                        selected = creatorState.homeInputMode == HomeInputMode.CREATOR,
                        onClick = { creatorViewModel.setHomeInputMode(HomeInputMode.CREATOR) },
                        label = { Text("查找作者") },
                    )
                }
            }
            if (creatorState.homeInputMode == HomeInputMode.WORK) {
                item {
                    OutlinedTextField(
                        value = uiState.inputText,
                        onValueChange = viewModel::setIncomingText,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("抖音、小红书、知乎、X、Instagram 或 B站分享链接") },
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
            } else {
                item {
                    CreatorSearchContent(creatorState, creatorViewModel, onShowCreators)
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
                    val recentTask = uiState.tasks.first()
                    OutlinedCard(
                        onClick = { onShowTasks(recentTask.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("最近任务", style = MaterialTheme.typography.titleMedium)
                            Text(
                                recentTask.stage,
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
            snapshotDelayMs = if (state.platform == SourcePlatform.XIAOHONGSHU) {
                XHS_WORK_SNAPSHOT_DELAY_MS
            } else {
                WEB_PAGE_SNAPSHOT_DELAY_MS
            },
            onReady = viewModel::parseWithCookies,
            onCancel = viewModel::resetParse,
        )

        ParseUiState.Parsing -> ParsingStatus("正在读取原始媒体和完整质量档位……")
        is ParseUiState.Ready -> ResultScreen(
            result = state.result,
            selectedVariant = uiState.selectedVariant,
            selectedAttachmentVariants = uiState.selectedAttachmentVariants,
            selectedBilibiliCids = uiState.selectedBilibiliCids,
            onBilibiliParts = viewModel::selectBilibiliParts,
            selectedMode = uiState.selectedMode,
            onVariant = viewModel::selectVariant,
            onAttachmentVariant = viewModel::selectAttachmentVariant,
            onMode = viewModel::selectMode,
            onDownload = { archiveAuthor ->
                val taskId = viewModel.queueDownload(state.result, archiveAuthor)
                onShowTasks(taskId)
            },
            onDownloadQuestion = { scope, includeComments ->
                val taskId = viewModel.queueQuestionArchive(state.result, scope, includeComments)
                onShowTasks(taskId)
            },
            onBack = viewModel::resetParse,
        )
        is ParseUiState.Error -> ErrorScreen(state, viewModel::retryParse, viewModel::resetParse)
    }
}

@Composable
private fun XiaohongshuCredentialProbe(onResult: (WebPageSnapshot?) -> Unit) {
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreatorSearchContent(
    state: CreatorLibraryUiState,
    viewModel: CreatorLibraryViewModel,
    onShowTasks: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("选择平台", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CREATOR_BATCH_PLATFORMS.forEach { platform ->
                FilterChip(
                    selected = state.queryPlatform == platform,
                    onClick = { viewModel.setQueryPlatform(platform) },
                    label = { Text(platform.displayName) },
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("作者主页链接 / 用户名") },
            supportingText = {
                    Text("粘贴作者主页链接；X、Instagram支持@用户名，B站支持UID")
            },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空作者输入")
                    }
                }
            },
        )
        Button(
            onClick = viewModel::findCreator,
            enabled = state.query.isNotBlank() && !state.isLoading,
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(if (state.isLoading) "正在查找" else "查找作者")
        }
        if (state.error.isNotBlank()) {
            Text(state.error, color = MaterialTheme.colorScheme.error)
        }
        state.candidate?.let { profile ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = profile.avatarUrl,
                        contentDescription = "${profile.nickname}头像",
                        modifier = Modifier.size(56.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlatformBrandBadge(profile.platform)
                            Spacer(Modifier.size(8.dp))
                            Text(profile.nickname, style = MaterialTheme.typography.titleMedium)
                        }
                        profile.accountId.takeIf(String::isNotBlank)?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(onClick = {
                        viewModel.confirmCreator(onShowTasks)
                    }) { Text("加入") }
                }
            }
        }
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
                val credentialState = states[platform] ?: PlatformCredentialState.NOT_DETECTED
                val detected = credentialState == PlatformCredentialState.DETECTED
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
                        when (credentialState) {
                            PlatformCredentialState.DETECTED -> "已登录"
                            PlatformCredentialState.ANONYMOUS -> "未登录（匿名会话）"
                            PlatformCredentialState.CHALLENGE_REQUIRED -> "需要完成安全验证"
                            PlatformCredentialState.EXPIRED -> "登录已失效"
                            PlatformCredentialState.CHECKING -> "正在验证登录"
                            PlatformCredentialState.UNVERIFIED -> "暂时无法验证"
                            PlatformCredentialState.NOT_DETECTED -> "未检测到登录"
                        },
                        color = if (detected) {
                            MaterialTheme.colorScheme.primary
                        } else if (credentialState == PlatformCredentialState.EXPIRED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                "登录状态会联网验证；未登录时部分作品可能解析失败。点击平台可登录或刷新环境。",
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

@Composable
private fun ResultScreen(
    result: ParseResult,
    selectedVariant: Int,
    selectedAttachmentVariants: Map<String, Int>,
    selectedBilibiliCids: Set<String>,
    onBilibiliParts: (Set<String>) -> Unit,
    selectedMode: DownloadMode,
    onVariant: (Int) -> Unit,
    onAttachmentVariant: (String, Int) -> Unit,
    onMode: (DownloadMode) -> Unit,
    onDownload: (Boolean) -> Unit,
    onDownloadQuestion: (ZhihuQuestionDownloadScope, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var questionScope by remember(result.contentId) {
        mutableStateOf(ZhihuQuestionDownloadScope.FIRST_PAGE)
    }
    var includeComments by remember(result.contentId) { mutableStateOf(true) }
    var archiveAuthor by remember(result.contentId) { mutableStateOf(false) }
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
            if (result.platform == SourcePlatform.BILIBILI && result.message.isNotBlank()) {
                Text(result.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result.description.isNotBlank()) {
                Text(
                    result.description,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (result.platform == SourcePlatform.BILIBILI && result.bilibiliParts.size > 1) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分P选择 · 已选 ${selectedBilibiliCids.size}/${result.bilibiliParts.size}", Modifier.weight(1f))
                    TextButton(onClick = { onBilibiliParts(result.bilibiliParts.map { it.cid }.toSet()) }) { Text("全选") }
                    TextButton(onClick = { onBilibiliParts(emptySet()) }) { Text("清空") }
                }
                Text("按稿件归为一张任务卡，各P独立下载、重试和删除。", style = MaterialTheme.typography.bodySmall)
            }
            items(result.bilibiliParts.size) { index ->
                val part = result.bilibiliParts[index]
                Row(Modifier.fillMaxWidth().clickable {
                    onBilibiliParts(if (part.cid in selectedBilibiliCids) selectedBilibiliCids - part.cid else selectedBilibiliCids + part.cid)
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = part.cid in selectedBilibiliCids, onCheckedChange = null)
                    Text("P${part.page} · ${part.title}", Modifier.padding(start = 8.dp))
                }
            }
        }
        if (result.attachments.isNotEmpty()) {
            item {
                Text(
                    "附件 ${result.attachments.size} 个 · 按帖子顺序保存",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(result.attachments.size) { attachmentPosition ->
                val attachment = result.attachments.sortedBy(MediaAttachment::index)[attachmentPosition]
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            when (attachment.kind) {
                                MediaAttachmentKind.IMAGE -> "附件 ${attachmentPosition + 1} · 原图"
                                MediaAttachmentKind.VIDEO -> "附件 ${attachmentPosition + 1} · 视频"
                                MediaAttachmentKind.GIF -> "附件 ${attachmentPosition + 1} · GIF（保存为无声 MP4）"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (attachment.coverUrl.isNotBlank()) {
                            AsyncImage(
                                model = attachment.coverUrl,
                                contentDescription = "附件 ${attachmentPosition + 1} 预览",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                            )
                        }
                        if (attachment.kind != MediaAttachmentKind.IMAGE) {
                            attachment.variants.forEachIndexed { index, variant ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selectedAttachmentVariants[attachment.id] == index,
                                        ) { onAttachmentVariant(attachment.id, index) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedAttachmentVariants[attachment.id] == index,
                                        onClick = { onAttachmentVariant(attachment.id, index) },
                                    )
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(variant.label)
                                        if (index == 0) {
                                            Text("最高档", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (result.attachments.any { it.kind == MediaAttachmentKind.VIDEO }) {
                item {
                    Text("视频保存模式（应用于全部视频附件）", style = MaterialTheme.typography.titleMedium)
                }
                items(videoModes(muxed = true)) { (mode, label) ->
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
        } else when (result.kind) {
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
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            if (result.question != null) {
                                Text("知乎问题归档", style = MaterialTheme.typography.titleMedium)
                                Text("当前可见回答约 ${result.question.answerCount} 条")
                                Text("每条回答保存为 Markdown，并生成问题总索引")
                            } else {
                                val images = document?.assets?.count {
                                    it.kind == DocumentAssetKind.IMAGE
                                } ?: 0
                                val videos = document?.assets?.count {
                                    it.kind == DocumentAssetKind.VIDEO
                                } ?: 0
                                Text(
                                    "知乎${document?.type?.displayLabel.orEmpty()}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text("正文将保存为 Markdown")
                                Text("图片 $images 张 · 内嵌视频 $videos 个")
                            }
                        }
                    }
                }
                if (result.question != null) {
                    item { Text("归档范围", style = MaterialTheme.typography.titleMedium) }
                    items(ZhihuQuestionDownloadScope.entries.size) { index ->
                        val scope = ZhihuQuestionDownloadScope.entries[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(questionScope == scope) { questionScope = scope }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = questionScope == scope,
                                onClick = { questionScope = scope },
                            )
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(if (scope == ZhihuQuestionDownloadScope.FIRST_PAGE) "仅第一页" else "全部可见回答")
                                if (scope == ZhihuQuestionDownloadScope.ALL) {
                                    Text(
                                        "回答较多时耗时较长，也更容易触发平台风控",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { includeComments = !includeComments },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = includeComments,
                                onCheckedChange = { includeComments = it },
                            )
                            Column {
                                Text("同时保存评论")
                                Text(
                                    "保存下载时当前账号可见的评论；可能增加耗时和风控概率",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
        if (result.question == null && result.authorStableId.isNotBlank()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { archiveAuthor = !archiveAuthor },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = archiveAuthor,
                        onCheckedChange = { archiveAuthor = it },
                    )
                    Column {
                        Text("归档作者到作者栏")
                        Text(
                            "关闭时不会新建作者入口；已有作者仍会关联该任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text("返回") }
                Button(
                    onClick = {
                        if (result.question != null) {
                            onDownloadQuestion(questionScope, includeComments)
                        } else {
                            onDownload(archiveAuthor)
                        }
                    },
                    enabled = if (result.platform == SourcePlatform.BILIBILI && result.bilibiliParts.size > 1 && selectedBilibiliCids.isEmpty()) false else if (result.attachments.isNotEmpty()) {
                        result.attachments.all { attachment ->
                            if (attachment.kind == MediaAttachmentKind.IMAGE) {
                                attachment.imageCandidates.isNotEmpty()
                            } else {
                                attachment.variants.isNotEmpty()
                            }
                        }
                    } else {
                        result.kind != MediaKind.VIDEO || result.variants.isNotEmpty()
                    },
                ) {
                    Text(
                        when (result.kind) {
                            MediaKind.IMAGE -> if (result.livePhotos.isEmpty()) "下载原图" else "下载原图和实况"
                            MediaKind.DOCUMENT -> if (result.question != null) {
                                "开始归档"
                            } else {
                                "下载完整内容"
                            }
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
    val noMedia = state.code == "MEDIA_EMPTY"
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (noMedia) "没有可下载媒体" else "解析失败",
            style = MaterialTheme.typography.headlineSmall,
            color = if (noMedia) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text("${state.code}：${state.message}")
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = back) { Text("返回") }
            if (!noMedia) {
                Button(onClick = retry) { Icon(Icons.Default.Refresh, null); Text("重试") }
            }
        }
    }
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
private const val WEB_PAGE_SNAPSHOT_DELAY_MS = 2_500L
private const val CREATOR_SEARCH_SNAPSHOT_DELAY_MS = 4_500L
internal const val CREATOR_BATCH_SNAPSHOT_DELAY_MS = 4_500L
private const val XHS_CREDENTIAL_PROBE_DELAY_MS = 2_000L
private const val XHS_CREDENTIAL_PROBE_TIMEOUT_MS = 12_000L
private const val XHS_WORK_SNAPSHOT_DELAY_MS = 4_500L

internal const val X_LOGIN_VIEWPORT_SCRIPT = """
    (function() {
      try {
        var DIAGNOSTIC_INPUT_SELECTOR = 'input[type="text"], input[type="email"], ' +
          'input[type="tel"], input[type="password"], input:not([type]), textarea';

        function diagnosticSafe(value, limit) {
          return String(value || '').replace(/[|~^,]/g, '_').slice(0, limit);
        }

        function diagnosticRect(element) {
          var rect = element.getBoundingClientRect();
          return [rect.left, rect.top, rect.right, rect.bottom, rect.width, rect.height]
            .map(function(value) { return Math.round(value); }).join(':');
        }

        function diagnosticVisible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width > 20 && rect.height > 5;
        }

        var diagnosticInputs = document.querySelectorAll(DIAGNOSTIC_INPUT_SELECTOR);
        var diagnosticTarget = document.activeElement;
        if (!diagnosticTarget || !diagnosticTarget.matches ||
            !diagnosticTarget.matches(DIAGNOSTIC_INPUT_SELECTOR) ||
            !diagnosticVisible(diagnosticTarget)) {
          diagnosticTarget = null;
          for (var diagnosticIndex = 0; diagnosticIndex < diagnosticInputs.length;
              diagnosticIndex += 1) {
            if (diagnosticVisible(diagnosticInputs[diagnosticIndex])) {
              diagnosticTarget = diagnosticInputs[diagnosticIndex];
              break;
            }
          }
        }

        var diagnosticViewport = window.visualViewport;
        var diagnosticViewportHeight = Math.round(Math.max(
          diagnosticViewport ? diagnosticViewport.height : 0,
          window.innerHeight || 0,
          document.documentElement ? document.documentElement.clientHeight : 0));
        var diagnosticViewportText = [
          diagnosticViewport ? diagnosticViewport.offsetLeft : 0,
          diagnosticViewport ? diagnosticViewport.offsetTop : 0,
          diagnosticViewport ? diagnosticViewport.width : window.innerWidth,
          diagnosticViewport ? diagnosticViewport.height : window.innerHeight,
          diagnosticViewport ? diagnosticViewport.scale : 1,
        ].map(function(value) { return Math.round(Number(value) * 100) / 100; }).join(':');

        function diagnosticFindScroller(target) {
          if (!target) return null;
          var diagnosticScroller = target.closest('.jf-vscroller');
          if (!diagnosticScroller) {
            var diagnosticCandidate = target.parentElement;
            while (diagnosticCandidate && diagnosticCandidate !== document.body) {
              var diagnosticCandidateStyle = window.getComputedStyle(diagnosticCandidate);
              var diagnosticCandidateRect = diagnosticCandidate.getBoundingClientRect();
              var diagnosticCandidateScrollable =
                diagnosticCandidateStyle.overflowY === 'scroll' ||
                diagnosticCandidateStyle.overflowY === 'auto';
              if (diagnosticCandidateScrollable && diagnosticCandidateRect.height <= 64 &&
                  diagnosticCandidate.scrollHeight > diagnosticCandidate.clientHeight * 2) {
                diagnosticScroller = diagnosticCandidate;
                break;
              }
              diagnosticCandidate = diagnosticCandidate.parentElement;
            }
          }
          return diagnosticScroller;
        }

        function diagnosticRepairScroller(target) {
          if (!target) return 'not-checked';
          var diagnosticScroller = diagnosticFindScroller(target);
          if (!diagnosticScroller) return 'not-found';
          var diagnosticLiveViewport = window.visualViewport;
          diagnosticViewportHeight = Math.round(Math.max(
            diagnosticLiveViewport ? diagnosticLiveViewport.height : 0,
            window.innerHeight || 0,
            document.documentElement ? document.documentElement.clientHeight : 0));
          var diagnosticScrollerBefore = diagnosticScroller.getBoundingClientRect();
          diagnosticScroller.style.setProperty('top', '0px', 'important');
          diagnosticScroller.style.setProperty('bottom', 'auto', 'important');
          diagnosticScroller.style.setProperty(
            'height', diagnosticViewportHeight + 'px', 'important');
          diagnosticScroller.style.setProperty(
            'min-height', diagnosticViewportHeight + 'px', 'important');
          diagnosticScroller.style.setProperty(
            'max-height', diagnosticViewportHeight + 'px', 'important');
          diagnosticScroller.style.setProperty('box-sizing', 'border-box', 'important');
          diagnosticScroller.style.setProperty('overflow-y', 'auto', 'important');
          diagnosticScroller.style.setProperty(
            '-webkit-overflow-scrolling', 'touch', 'important');
          diagnosticScroller.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
          var diagnosticScrollerAfter = diagnosticScroller.getBoundingClientRect();
          return 'expanded-' + Math.round(diagnosticScrollerBefore.height) + '-' +
            Math.round(diagnosticScrollerAfter.height) + '-target-' + diagnosticViewportHeight;
        }

        var diagnosticScrollerState = diagnosticRepairScroller(diagnosticTarget);

        if (!window.__aggregateXScrollerWatcher) {
          var diagnosticWatcherPending = 0;
          function diagnosticWatcherTarget() {
            var active = document.activeElement;
            if (active && active.matches && active.matches(DIAGNOSTIC_INPUT_SELECTOR) &&
                diagnosticVisible(active)) {
              return active;
            }
            var candidates = document.querySelectorAll(DIAGNOSTIC_INPUT_SELECTOR);
            for (var candidateIndex = 0; candidateIndex < candidates.length; candidateIndex += 1) {
              if (diagnosticVisible(candidates[candidateIndex])) return candidates[candidateIndex];
            }
            return null;
          }
          function diagnosticWatcherRun() {
            var target = diagnosticWatcherTarget();
            diagnosticRepairScroller(target);
            if (target && document.activeElement === target) {
              window.requestAnimationFrame(function() {
                target.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'auto'});
              });
            }
          }
          function diagnosticWatcherSchedule() {
            window.clearTimeout(diagnosticWatcherPending);
            diagnosticWatcherPending = window.setTimeout(diagnosticWatcherRun, 80);
          }
          document.addEventListener('focusin', diagnosticWatcherSchedule, true);
          if (window.visualViewport) {
            window.visualViewport.addEventListener('resize', diagnosticWatcherSchedule);
          }
          var diagnosticWatcherObserver = new MutationObserver(diagnosticWatcherSchedule);
          diagnosticWatcherObserver.observe(
            document.documentElement, {childList: true, subtree: true});
          window.__aggregateXScrollerWatcher = {
            schedule: diagnosticWatcherSchedule,
            observer: diagnosticWatcherObserver,
            marker: 'x-scroller-watch-v1',
          };
        } else {
          window.__aggregateXScrollerWatcher.schedule();
        }

        var diagnosticResult;
        if (!diagnosticTarget) {
          diagnosticResult = 'waiting-for-input|ready=' + document.readyState +
            '|inputs=' + diagnosticInputs.length + '|viewport=' + diagnosticViewportText +
            '|body=' + (document.body ? document.body.children.length : -1) +
            '|x-dom-diagnostic-v1';
        } else {
          var diagnosticAncestors = [];
          var diagnosticNode = diagnosticTarget;
          for (var diagnosticDepth = 0;
              diagnosticNode && diagnosticDepth < 10;
              diagnosticDepth += 1) {
            var diagnosticStyle = window.getComputedStyle(diagnosticNode);
            diagnosticAncestors.push([
              diagnosticDepth + ':' + diagnosticNode.tagName.toLowerCase(),
              'id=' + diagnosticSafe(diagnosticNode.id, 48),
              'class=' + diagnosticSafe(diagnosticNode.className, 100),
              'role=' + diagnosticSafe(diagnosticNode.getAttribute('role'), 32),
              'rect=' + diagnosticRect(diagnosticNode),
              'position=' + diagnosticStyle.position,
              'display=' + diagnosticStyle.display,
              'transform=' + diagnosticSafe(diagnosticStyle.transform, 96),
              'overflow=' + diagnosticStyle.overflowX + '/' + diagnosticStyle.overflowY,
              'margin=' + diagnosticStyle.marginTop + '/' + diagnosticStyle.marginBottom,
              'padding=' + diagnosticStyle.paddingTop + '/' + diagnosticStyle.paddingBottom,
              'scroll=' + diagnosticNode.scrollTop + '/' + diagnosticNode.scrollHeight +
                '/' + diagnosticNode.clientHeight,
            ].join('~'));
            diagnosticNode = diagnosticNode.parentElement;
          }
          diagnosticResult = 'input-found|ready=' + document.readyState +
            '|viewport=' + diagnosticViewportText +
            '|type=' + diagnosticSafe(diagnosticTarget.getAttribute('type'), 24) +
            '|name=' + diagnosticSafe(diagnosticTarget.getAttribute('name'), 48) +
            '|autocomplete=' + diagnosticSafe(
              diagnosticTarget.getAttribute('autocomplete'), 48) +
            '|ancestors=' + diagnosticAncestors.join('^') + '|x-dom-diagnostic-v1';
        }
        diagnosticResult += '|vscroller=' + diagnosticScrollerState +
          '|watcher=x-scroller-watch-v1|x-vscroller-fix-v1';
        return diagnosticResult;

        if (window.__aggregateXLoginAssist &&
            typeof window.__aggregateXLoginAssist.run === 'function') {
          return window.__aggregateXLoginAssist.run();
        }
        var pending = 0;
        var dialogState = 'not-checked';
        var INPUT_SELECTOR = 'input[type="text"], input[type="email"], input[type="tel"], ' +
          'input[type="password"], input:not([type]), textarea';

        function visible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width > 80 && rect.height > 20;
        }

        function currentInput() {
          var active = document.activeElement;
          if (active && active.matches &&
              active.matches(INPUT_SELECTOR) && visible(active)) {
            return active;
          }
          var inputs = document.querySelectorAll(INPUT_SELECTOR);
          for (var i = 0; i < inputs.length; i += 1) {
            if (visible(inputs[i])) return inputs[i];
          }
          return null;
        }

        function centerScrollableParents(target) {
          var parent = target.parentElement;
          while (parent && parent !== document.body && parent !== document.documentElement) {
            if (parent.scrollHeight > parent.clientHeight + 8) {
              var parentRect = parent.getBoundingClientRect();
              var targetRect = target.getBoundingClientRect();
              parent.scrollTop += targetRect.top - parentRect.top -
                (parent.clientHeight - targetRect.height) / 2;
            }
            parent = parent.parentElement;
          }
        }

        function stabilizeLoginDialog(target) {
          var viewport = window.visualViewport;
          var viewportTop = viewport ? viewport.offsetTop : 0;
          var viewportHeight = viewport ? viewport.height : window.innerHeight;
          var targetRect = target.getBoundingClientRect();
          var safeTop = viewportTop + 24;
          var safeBottom = viewportTop + viewportHeight - 24;
          if (targetRect.top >= safeTop && targetRect.bottom <= safeBottom) {
            dialogState = 'in-bounds';
            return false;
          }
          var dialog = target.closest('[role="dialog"]');
          var fallback = false;
          var fallbackPosition = '';
          if (!dialog) {
            var ancestor = target.parentElement;
            while (ancestor && ancestor !== document.body) {
              var ancestorStyle = window.getComputedStyle(ancestor);
              var position = ancestorStyle.position;
              if (position === 'fixed') {
                dialog = ancestor;
                break;
              }
              var ancestorRect = ancestor.getBoundingClientRect();
              var transformed = ancestorStyle.transform && ancestorStyle.transform !== 'none';
              var clipped = ancestorStyle.overflowY === 'hidden' ||
                ancestorStyle.overflowY === 'clip';
              var offscreen = ancestorRect.top < safeTop &&
                ancestorRect.bottom > viewportTop && ancestorRect.height >= targetRect.height;
              if (!dialog && ancestorRect.width >= targetRect.width &&
                  ancestorRect.height >= targetRect.height &&
                  (transformed || position === 'absolute' || position === 'sticky' || clipped ||
                    offscreen)) {
                dialog = ancestor;
                fallback = true;
                fallbackPosition = transformed ? 'transform' :
                  (clipped ? 'clipped' : (offscreen ? 'offscreen' : position));
              }
              ancestor = ancestor.parentElement;
            }
          }
          if (!dialog) {
            dialogState = 'container-missing';
            return false;
          }
          if (fallback) {
            var desiredTop = viewportTop + (viewportHeight - targetRect.height) / 2;
            var previousShift = Number(dialog.getAttribute('data-aggregate-shift-y') || 0);
            var delta = desiredTop - targetRect.top;
            var nextShift = Math.max(-viewportHeight, Math.min(viewportHeight, previousShift + delta));
            dialog.style.setProperty('translate', '0px ' + nextShift + 'px', 'important');
            dialog.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
            dialog.setAttribute('data-aggregate-shift-y', String(nextShift));
            dialogState = fallbackPosition === 'offscreen' ?
              'shifted-offscreen' : 'shifted-' + fallbackPosition;
            return true;
          }
          dialog.style.setProperty('position', 'fixed', 'important');
          dialog.style.setProperty('top', viewportTop + 'px', 'important');
          dialog.style.setProperty('bottom', 'auto', 'important');
          dialog.style.setProperty('left', '0px', 'important');
          dialog.style.setProperty('right', 'auto', 'important');
          dialog.style.setProperty('width', '100%', 'important');
          dialog.style.setProperty('height', viewportHeight + 'px', 'important');
          dialog.style.setProperty('max-height', viewportHeight + 'px', 'important');
          dialog.style.setProperty('margin', '0px', 'important');
          dialog.style.setProperty('transform', 'none', 'important');
          dialog.style.setProperty('overflow-y', 'auto', 'important');
          dialog.style.setProperty('overscroll-behavior-y', 'contain', 'important');
          dialog.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');
          dialog.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
          dialogState = 'adjusted';
          return true;
        }

        function centerInput() {
          var target = currentInput();
          if (!target) return false;
          target.style.setProperty('scroll-margin-top', '96px', 'important');
          target.style.setProperty('scroll-margin-bottom', '96px', 'important');
          stabilizeLoginDialog(target);
          centerScrollableParents(target);
          target.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'auto'});
          window.requestAnimationFrame(function() {
            var viewport = window.visualViewport;
            var viewportTop = viewport ? viewport.offsetTop : 0;
            var viewportHeight = viewport ? viewport.height : window.innerHeight;
            var rect = target.getBoundingClientRect();
            var desiredTop = viewportTop + (viewportHeight - rect.height) / 2;
            if (rect.top < viewportTop + 24 || rect.bottom > viewportTop + viewportHeight - 24) {
              window.scrollBy(0, rect.top - desiredTop);
              centerScrollableParents(target);
            }
          });
          return true;
        }

        function scheduleCenter() {
          window.clearTimeout(pending);
          pending = window.setTimeout(centerInput, 120);
          window.setTimeout(centerInput, 420);
          window.setTimeout(centerInput, 780);
        }

        document.addEventListener('focusin', function(event) {
          if (event.target && event.target.matches &&
              event.target.matches(INPUT_SELECTOR)) {
            scheduleCenter();
          }
        }, true);

        if (window.visualViewport) {
          window.visualViewport.addEventListener('resize', scheduleCenter);
        }

        var observer = new MutationObserver(scheduleCenter);
        observer.observe(document.documentElement, {childList: true, subtree: true});
        window.setTimeout(function() { observer.disconnect(); }, 30000);

        function runAndReport() {
          dialogState = 'not-checked';
          var centered = centerInput();
          var target = currentInput();
          var viewport = window.visualViewport;
          var viewportTop = viewport ? viewport.offsetTop : 0;
          var viewportHeight = viewport ? viewport.height : window.innerHeight;
          var geometry = 'none';
          if (target) {
            var rect = target.getBoundingClientRect();
            geometry = Math.round(rect.top) + ',' + Math.round(rect.bottom) + ',' +
              Math.round(viewportTop) + ',' + Math.round(viewportHeight);
          }
          return (centered ? 'input-centered' : 'waiting-for-input') +
            '|dialog=' + dialogState + '|target=' + geometry + '|x-layout-v6';
        }

        window.__aggregateXLoginAssist = {run: runAndReport};

        var attempts = 0;
        var timer = window.setInterval(function() {
          attempts += 1;
          if (centerInput() || attempts >= 20) window.clearInterval(timer);
        }, 300);
        return runAndReport();
      } catch (error) {
        return 'failed';
      }
    })();
"""

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

        function visibleControl(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width >= 32 && rect.height >= 24;
        }

        function openLogin() {
          var controls = document.querySelectorAll('button, a, [role="button"]');
          for (var i = 0; i < controls.length; i += 1) {
            var text = (controls[i].innerText || controls[i].textContent || '')
              .trim().replace(/\s+/g, '');
            if (visibleControl(controls[i]) &&
                (text === '登录' || text === '登录/注册' || text === '注册/登录')) {
              controls[i].click();
              return true;
            }
          }
          return false;
        }

        var clicked = false;
        function prepareLogin() {
          if (centerLogin()) return true;
          if (!clicked) clicked = openLogin();
          return false;
        }

        if (prepareLogin()) return 'centered';
        var attempts = 0;
        var timer = window.setInterval(function() {
          attempts += 1;
          if (prepareLogin() || attempts >= 20) window.clearInterval(timer);
        }, 500);
        return clicked ? 'login-clicked' : 'waiting-for-login-control';
      } catch (error) {
        return 'failed';
      }
    })();
"""

internal const val XHS_LOGIN_VIEWPORT_SCRIPT = """
    (function() {
      try {
        function visible(element) {
          if (!element) return false;
          var style = window.getComputedStyle(element);
          var rect = element.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' &&
            Number(style.opacity || 1) > 0 && rect.width > 40 && rect.height > 24;
        }

        function unlockScrolling(element, constrainHeight) {
          if (!element) return;
          element.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
          element.style.setProperty('overscroll-behavior-y', 'auto', 'important');
          element.style.setProperty('-webkit-overflow-scrolling', 'touch', 'important');
          if (constrainHeight) {
            element.style.setProperty('max-height', 'calc(100vh - 32px)', 'important');
            element.style.setProperty('overflow-y', 'auto', 'important');
          }
        }

        function unlockPageScrolling() {
          unlockScrolling(document.documentElement, false);
          unlockScrolling(document.body, false);
          if (document.documentElement) {
            document.documentElement.style.setProperty('overflow-y', 'auto', 'important');
          }
          if (document.body) {
            document.body.style.setProperty('overflow-y', 'auto', 'important');
          }
        }

        function unlockPanelScrolling(element) {
          var current = element;
          var levels = 0;
          while (current && current !== document.body && levels < 5) {
            unlockScrolling(current, levels < 2);
            current = current.parentElement;
            levels += 1;
          }
        }

        unlockPageScrolling();

        function center(element) {
          if (!visible(element)) return false;
          unlockPanelScrolling(element);
          element.scrollIntoView({block: 'center', inline: 'center', behavior: 'auto'});
          window.requestAnimationFrame(function() {
            var rect = element.getBoundingClientRect();
            window.scrollBy(
              rect.left + rect.width / 2 - window.innerWidth / 2,
              rect.top + rect.height / 2 - window.innerHeight / 2
            );
          });
          return true;
        }

        function isCaptchaPage() {
          return /\/website-login\/captcha(?:\/|$)/i.test(window.location.pathname || '');
        }

        function revealCaptcha() {
          var root = document.documentElement;
          var body = document.body;
          if (root) {
            root.style.setProperty('overflow-y', 'auto', 'important');
            root.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
            root.style.setProperty('height', 'auto', 'important');
            root.style.setProperty('min-height', '100%', 'important');
          }
          if (body) {
            body.style.setProperty('overflow-y', 'auto', 'important');
            body.style.setProperty('touch-action', 'pan-y pinch-zoom', 'important');
            body.style.setProperty('height', 'auto', 'important');
            body.style.setProperty('min-height', '100vh', 'important');
            body.style.setProperty('padding-top', '16px', 'important');
            body.style.setProperty('box-sizing', 'border-box', 'important');
          }
          window.scrollTo(0, 0);

          var elements = document.querySelectorAll('body *');
          var anchor = null;
          for (var i = 0; i < elements.length; i += 1) {
            var text = (elements[i].innerText || '').trim();
            if (visible(elements[i]) && (text === '刷新' || text === '问题反馈')) {
              anchor = elements[i];
              break;
            }
          }
          if (!anchor) return false;

          var panel = anchor;
          var candidate = anchor;
          while (candidate && candidate !== body) {
            var rect = candidate.getBoundingClientRect();
            var candidateText = (candidate.innerText || '').trim();
            if (rect.width >= 240 && rect.height >= 160 && candidateText.length < 1200) {
              panel = candidate;
            }
            candidate = candidate.parentElement;
          }
          var panelRect = panel.getBoundingClientRect();
          unlockPanelScrolling(panel);
          if (panelRect.top < 16) {
            panel.style.setProperty(
              'translate',
              '0px ' + Math.ceil(16 - panelRect.top) + 'px',
              'important'
            );
          }
          panel.scrollIntoView({block: 'start', inline: 'center', behavior: 'auto'});
          window.scrollBy(0, -16);
          return true;
        }

        function findLoginPanel() {
          var input = document.querySelector(
            'input[type="tel"], input[placeholder*="手机号"], input[placeholder*="手机号码"]'
          );
          if (visible(input)) {
            return input.closest('[role="dialog"], [class*="login"], [class*="Login"], form') || input;
          }
          var selectors = [
            '[role="dialog"]',
            '[class*="login-container"]',
            '[class*="login-modal"]',
            '[class*="login-content"]',
            '[class*="Login"]'
          ];
          for (var i = 0; i < selectors.length; i += 1) {
            var elements = document.querySelectorAll(selectors[i]);
            for (var j = 0; j < elements.length; j += 1) {
              var text = (elements[j].innerText || '').slice(0, 500);
              if (visible(elements[j]) &&
                  (text.indexOf('手机号') >= 0 || text.indexOf('验证码') >= 0 ||
                   text.indexOf('扫码') >= 0)) {
                return elements[j];
              }
            }
          }
          return null;
        }

        function openLogin() {
          var elements = document.querySelectorAll('button, a, [role="button"]');
          for (var i = 0; i < elements.length; i += 1) {
            var text = (elements[i].innerText || '').trim().replace(/\s+/g, '');
            if (visible(elements[i]) &&
                (text === '登录' || text.indexOf('登录探索更多内容') >= 0)) {
              elements[i].click();
              return true;
            }
          }
          return false;
        }

        var attempts = 0;
        if (isCaptchaPage()) {
          if (revealCaptcha()) return 'captcha-visible';
          var captchaTimer = window.setInterval(function() {
            attempts += 1;
            if (revealCaptcha() || attempts >= 24) window.clearInterval(captchaTimer);
          }, 500);
          return 'captcha-waiting';
        }

        var clicked = false;
        function prepareLogin() {
          var panel = findLoginPanel();
          if (panel && center(panel)) return true;
          if (!clicked) clicked = openLogin();
          return false;
        }

        if (prepareLogin()) return 'centered';
        var timer = window.setInterval(function() {
          attempts += 1;
          if (prepareLogin() || attempts >= 24) window.clearInterval(timer);
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
      var isXiaohongshu = /(^|\.)xiaohongshu\.com$/i.test(window.location.hostname || '');
      var targetNoteMatch = path.match(
        /\/(?:explore|discovery\/item|note)\/([0-9a-zA-Z_-]+)/i
      );
      var targetNoteId = targetNoteMatch ? targetNoteMatch[1] : '';
      if (isXiaohongshu) {
        try {
          var stateCandidates = [];
          function addStateCandidate(value) {
            if (!value || typeof value !== 'string' || value.length > 5000000) return;
            stateCandidates.push(value);
          }
          addStateCandidate(initialData);
          if (window.__INITIAL_STATE__) {
            try { addStateCandidate(JSON.stringify(window.__INITIAL_STATE__)); } catch (_) {}
          }
          Array.prototype.forEach.call(document.scripts || [], function(script) {
            var text = script.textContent || '';
            if (text.indexOf('__INITIAL_STATE__') >= 0 ||
                (targetNoteId && text.indexOf(targetNoteId) >= 0)) {
              addStateCandidate(text);
            }
          });
          stateCandidates.sort(function(left, right) {
            function score(value) {
              return (targetNoteId && value.indexOf(targetNoteId) >= 0 ? 10000000 : 0) +
                value.length;
            }
            return score(right) - score(left);
          });
          if (stateCandidates.length) initialData = stateCandidates[0];
        } catch (_) {}
      }
      if ((!initialData || (targetNoteId && initialData.indexOf(targetNoteId) < 0)) &&
          isXiaohongshu) {
        try {
          var seenNotes = {};
          var notes = [];
          var links = document.querySelectorAll(
            'a[href*="/explore/"], a[href*="/discovery/item/"]'
          );
          Array.prototype.forEach.call(links, function(link) {
            var href = link.href || link.getAttribute('href') || '';
            var match = href.match(/\/(?:explore|discovery\/item)\/([0-9a-f]{24})/i);
            if (!match || seenNotes[match[1]]) return;
            seenNotes[match[1]] = true;
            var card = link.closest(
              '[class*="note-item"], [class*="noteItem"], article, li, section'
            ) || link.parentElement || link;
            var image = card.querySelector('img') || link.querySelector('img');
            var titleNode = card.querySelector(
              '[class*="title"], [class*="desc"], [class*="content"]'
            );
            var token = '';
            try { token = new URL(href, window.location.href).searchParams.get('xsec_token') || ''; }
            catch (_) {}
            notes.push({ noteCard: {
              noteId: match[1],
              displayTitle: titleNode ? (titleNode.textContent || '').trim() :
                (image ? (image.alt || '').trim() : ''),
              type: card.querySelector('video, [class*="play"], [class*="video"]') ?
                'video' : 'normal',
              xsecToken: token,
              cover: { urlDefault: image ?
                (image.currentSrc || image.src || image.getAttribute('data-src') || '') : '' }
            }});
          });
          if (notes.length) {
            initialData = JSON.stringify({ user: { userPageData: { notes: notes } } });
          }
        } catch (_) {}
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

private const val XHS_CREDENTIAL_SNAPSHOT_SCRIPT = """
    (function() {
      var path = window.location.pathname || '';
      var bodyText = document.body ? (document.body.innerText || '') : '';
      var accountLink = document.querySelector(
        'header a[href*="/user/profile/"], nav a[href*="/user/profile/"], ' +
        '[class*="side-bar"] a[href*="/user/profile/"], ' +
        '[class*="sidebar"] a[href*="/user/profile/"], ' +
        '[class*="user-container"] a[href*="/user/profile/"]'
      );
      var loginControl = document.querySelector(
        'input[type="tel"], input[placeholder*="手机号"], ' +
        '[class*="login-container"], [class*="login-modal"], [class*="login-btn"]'
      );
      var stateText = '';
      if (window.__INITIAL_STATE__) {
        try { stateText = JSON.stringify(window.__INITIAL_STATE__); } catch (_) {}
      }
      var stateLoggedIn = /"guest"\s*:\s*false/i.test(stateText) &&
        /"user_?[iI]d"\s*:\s*"[^"\s]+"/.test(stateText);
      var challengeRequired = /\/website-login\/captcha(?:\/|$)/i.test(path) ||
        /请完成.{0,12}验证|安全验证/.test(bodyText.slice(0, 2000));
      var loginPrompt = /\/login(?:\/|$)/i.test(path) || !!loginControl ||
        /登录探索更多内容|手机号登录|扫码登录/.test(bodyText.slice(0, 4000));
      return JSON.stringify({
        finalUrl: window.location.href || '',
        initialData: JSON.stringify({
          loggedIn: !!accountLink || stateLoggedIn,
          challengeRequired: challengeRequired,
          loginPrompt: loginPrompt
        }),
        title: document.title || '',
        author: '',
        contentHtml: '',
        visibleText: bodyText.slice(0, 500)
      });
    })();
"""
