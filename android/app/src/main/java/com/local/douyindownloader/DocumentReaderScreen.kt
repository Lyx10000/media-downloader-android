package com.local.douyindownloader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.File

private sealed interface DocumentReaderState {
    data object Loading : DocumentReaderState
    data object Unavailable : DocumentReaderState
    data class Ready(val data: DocumentReaderData) : DocumentReaderState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentReaderScreen(
    task: TaskRecord,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var state by remember(task.id, task.outputs) {
        mutableStateOf<DocumentReaderState>(DocumentReaderState.Loading)
    }
    LaunchedEffect(task.id, task.outputs) {
        state = viewModel.loadDocumentReader(task)
            ?.let(DocumentReaderState::Ready)
            ?: DocumentReaderState.Unavailable
    }
    BackHandler(onBack = onBack)
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(task.title.ifBlank { "知乎文档" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回文件管理")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                DocumentReaderState.Loading -> CircularProgressIndicator()
                DocumentReaderState.Unavailable -> Text("无法读取文档，任务记录或文件可能已被删除")
                is DocumentReaderState.Ready -> DocumentWebView(
                    data = current.data,
                    taskId = task.id,
                    viewModel = viewModel,
                    context = context,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun DocumentWebView(
    data: DocumentReaderData,
    taskId: String,
    viewModel: MainViewModel,
    context: Context,
    modifier: Modifier,
) {
    val outputs = data.assetOutputs
    val localUrls = remember(outputs) {
        outputs.mapValues { (assetId, _) ->
            "$ASSET_ORIGIN$ASSET_PATH${Uri.encode(assetId)}"
        }
    }
    val html = remember(data.document, localUrls) {
        DocumentHtmlRenderer.render(data.document, localUrls)
    }
    val assetLoader = remember(outputs) {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler(ASSET_PATH, DocumentMediaPathHandler(context, outputs))
            .build()
    }
    val client = remember(assetLoader, outputs, taskId) {
        object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == MEDIA_SCHEME && uri.host == "open") {
                    val assetId = Uri.decode(uri.pathSegments.firstOrNull().orEmpty())
                    outputs[assetId]?.let { viewModel.openDocumentMedia(context, taskId, it) }
                    return true
                }
                if (uri.host == ASSET_DOMAIN) return false
                if (uri.scheme in setOf("http", "https")) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        .onFailure { viewModel.showMessage("没有可打开该链接的应用") }
                }
                return true
            }
        }
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { factoryContext ->
            WebView(factoryContext).apply {
                webView = this
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = true
                webViewClient = client
                tag = html.hashCode()
                loadDataWithBaseURL(ASSET_ORIGIN, html, "text/html", "UTF-8", null)
            }
        },
        update = { view ->
            if (view.tag != html.hashCode()) {
                view.tag = html.hashCode()
                view.loadDataWithBaseURL(ASSET_ORIGIN, html, "text/html", "UTF-8", null)
            }
        },
    )
}

private class DocumentMediaPathHandler(
    context: Context,
    private val outputs: Map<String, TaskOutput>,
) : WebViewAssetLoader.PathHandler {
    private val resolver = context.applicationContext.contentResolver

    override fun handle(path: String): WebResourceResponse? {
        val assetId = Uri.decode(path.substringBefore('/'))
        val output = outputs[assetId] ?: return null
        val uri = runCatching { Uri.parse(output.uri) }.getOrNull() ?: return null
        val input = runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let(::File)?.inputStream()
                else -> resolver.openInputStream(uri)
            }
        }.getOrNull() ?: return null
        return WebResourceResponse(
            mediaMimeType(output.displayName, output.mimeType),
            null,
            input,
        )
    }
}

private const val ASSET_DOMAIN = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "https://$ASSET_DOMAIN/"
private const val ASSET_PATH = "/document-media/"
private const val MEDIA_SCHEME = "app-media"
