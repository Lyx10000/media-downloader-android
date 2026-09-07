package com.local.multiplatformdownloader.feature.document

import com.local.multiplatformdownloader.core.model.DocumentAsset
import com.local.multiplatformdownloader.core.model.DocumentBlockType
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.feature.home.MainViewModel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import org.jsoup.Jsoup

private sealed interface DocumentReaderState {
    data object Loading : DocumentReaderState
    data object Unavailable : DocumentReaderState
    data class Ready(val data: DocumentReaderData) : DocumentReaderState
    data class MarkdownReady(val title: String, val text: String) : DocumentReaderState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentReaderScreen(
    task: TaskRecord,
    selectedOutput: TaskOutput?,
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = remember(context.applicationContext) {
        ImageLoader.Builder(context.applicationContext).crossfade(true).build()
    }
    var state by remember(task.id, task.outputs, selectedOutput?.uri) {
        mutableStateOf<DocumentReaderState>(DocumentReaderState.Loading)
    }
    var openedImageId by remember(task.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(task.id, task.outputs, selectedOutput?.uri) {
        state = if (selectedOutput?.displayName.equals("comments.md", ignoreCase = true)) {
            viewModel.loadMarkdownOutput(task.id, selectedOutput!!)
                ?.let { DocumentReaderState.MarkdownReady("评论", it) }
                ?: DocumentReaderState.Unavailable
        } else {
            viewModel.loadDocumentReader(task)
                ?.let(DocumentReaderState::Ready)
                ?: DocumentReaderState.Unavailable
        }
    }
    DisposableEffect(imageLoader) { onDispose(imageLoader::shutdown) }
    BackHandler {
        if (openedImageId != null) openedImageId = null else onBack()
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    val title = (state as? DocumentReaderState.MarkdownReady)?.title.orEmpty()
                    if (title.isNotBlank()) Text(title)
                },
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
                is DocumentReaderState.Ready -> NativeDocumentContent(
                    taskId = task.id,
                    data = current.data,
                    imageLoader = imageLoader,
                    viewModel = viewModel,
                    onOpenImage = { openedImageId = it },
                )
                is DocumentReaderState.MarkdownReady -> MarkdownCommentContent(current.text)
            }
        }
    }
    val ready = state as? DocumentReaderState.Ready
    val images = ready?.data?.let(::resolveDocumentReaderImages).orEmpty()
    openedImageId?.let { assetId ->
        if (images.isNotEmpty()) {
            DocumentImageViewer(
                taskId = task.id,
                images = images,
                initialPage = documentImageStartIndex(images, assetId),
                imageLoader = imageLoader,
                viewModel = viewModel,
                onDismiss = { openedImageId = null },
            )
        }
    }
}

internal enum class MarkdownLineType { HEADING, QUOTE, TEXT }

internal data class MarkdownDisplayLine(
    val type: MarkdownLineType,
    val text: String,
)

@Composable
private fun MarkdownCommentContent(markdown: String) {
    val lines = remember(markdown) { parseMarkdownDisplayLines(markdown) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lines) { line ->
            when (line.type) {
                MarkdownLineType.HEADING -> Text(
                    line.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
                MarkdownLineType.QUOTE -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        line.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                MarkdownLineType.TEXT -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.25f,
                )
            }
        }
    }
}

internal fun parseMarkdownDisplayLines(markdown: String): List<MarkdownDisplayLine> = markdown
    .lineSequence()
    .map(String::trimEnd)
    .filter(String::isNotBlank)
    .map { raw ->
        when {
            raw.startsWith("#") -> MarkdownDisplayLine(
                MarkdownLineType.HEADING,
                documentDisplayText(raw.trimStart('#').trim()),
            )
            raw.startsWith(">") -> MarkdownDisplayLine(
                MarkdownLineType.QUOTE,
                documentDisplayText(raw.removePrefix(">").trim()),
            )
            else -> MarkdownDisplayLine(MarkdownLineType.TEXT, documentDisplayText(raw))
        }
    }
    .filter { it.text.isNotBlank() }
    .toList()

@Composable
private fun NativeDocumentContent(
    taskId: String,
    data: DocumentReaderData,
    imageLoader: ImageLoader,
    viewModel: MainViewModel,
    onOpenImage: (String) -> Unit,
) {
    val context = LocalContext.current
    val assets = remember(data.document.assets) { data.document.assets.associateBy(DocumentAsset::id) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 16.dp, end = 18.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                data.document.title.ifBlank { "知乎内容" },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (data.document.author.isNotBlank()) {
            item {
                Text(
                    "作者：${data.document.author}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (data.document.sourceUrl.isNotBlank()) {
            item {
                TextButton(onClick = { openExternalUrl(context, data.document.sourceUrl, viewModel) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("查看知乎原文")
                }
            }
        }
        item { HorizontalDivider() }
        items(data.document.blocks) { block ->
            when (block.type) {
                DocumentBlockType.PARAGRAPH -> DocumentParagraph(block.text)
                DocumentBlockType.HEADING -> DocumentHeading(block.text, block.level)
                DocumentBlockType.LIST -> DocumentList(block.text)
                DocumentBlockType.BLOCKQUOTE -> DocumentQuote(block.text)
                DocumentBlockType.CODE -> DocumentCode(block.text, block.language)
                DocumentBlockType.HTML -> DocumentHtmlText(block.text)
                DocumentBlockType.IMAGE -> assets[block.assetId]?.let { asset ->
                    DocumentImageBlock(
                        taskId = taskId,
                        image = DocumentReaderImage(asset, data.assetOutputs[asset.id]),
                        imageLoader = imageLoader,
                        viewModel = viewModel,
                        onClick = { onOpenImage(asset.id) },
                    )
                }
                DocumentBlockType.VIDEO -> assets[block.assetId]?.let { asset ->
                    DocumentVideoBlock(
                        taskId = taskId,
                        asset = asset,
                        output = data.assetOutputs[asset.id],
                        viewModel = viewModel,
                    )
                }
            }
        }
        if (data.document.warnings.isNotEmpty()) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("下载提示", style = MaterialTheme.typography.titleMedium)
                        data.document.warnings.distinct().forEach { warning -> Text("• $warning") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentParagraph(text: String) {
    Text(
        documentDisplayText(text),
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.35f,
    )
}

@Composable
private fun DocumentHeading(text: String, level: Int) {
    Text(
        documentDisplayText(text),
        style = when (level.coerceIn(1, 6)) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        },
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun DocumentList(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        text.lines().filter(String::isNotBlank).forEach { line ->
            Text(documentDisplayText(line), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DocumentQuote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            documentDisplayText(text),
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DocumentCode(text: String, language: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (language.isNotBlank()) {
                Text(language, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DocumentHtmlText(html: String) {
    val text = remember(html) { Jsoup.parseBodyFragment(html).text() }
    if (text.isNotBlank()) DocumentParagraph(text)
}

@Composable
private fun DocumentImageBlock(
    taskId: String,
    image: DocumentReaderImage,
    imageLoader: ImageLoader,
    viewModel: MainViewModel,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DocumentImageContent(
            taskId = taskId,
            image = image,
            imageLoader = imageLoader,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 680.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .clickable(onClick = onClick),
            contentDescription = image.asset.alt.ifBlank { "正文图片" },
        )
        if (image.asset.alt.isNotBlank()) {
            Text(
                image.asset.alt,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DocumentImageContent(
    taskId: String,
    image: DocumentReaderImage,
    imageLoader: ImageLoader,
    viewModel: MainViewModel,
    modifier: Modifier,
    contentDescription: String,
) {
    val context = LocalContext.current
    val localUri = remember(image.output?.uri) {
        image.output?.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    val remoteUrl = image.asset.candidateUrls.firstOrNull()
    var useRemote by remember(image.asset.id, localUri, remoteUrl) {
        mutableStateOf(localUri == null)
    }
    val source: Any? = if (useRemote) remoteUrl else localUri
    val model = remember(source) {
        ImageRequest.Builder(context)
            .data(source)
            .build()
    }
    SubcomposeAsyncImage(
        model = model,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("图片加载失败", color = MaterialTheme.colorScheme.error)
            }
        },
        onError = { state ->
            viewModel.onDocumentImageLoadFailed(
                taskId = taskId,
                assetId = image.asset.id,
                outputName = image.output?.displayName.orEmpty(),
                sourceScheme = when (source) {
                    is Uri -> source.scheme.orEmpty()
                    is String -> runCatching { Uri.parse(source).scheme }.getOrNull().orEmpty()
                    else -> ""
                },
                error = state.result.throwable,
            )
            if (!useRemote && !remoteUrl.isNullOrBlank()) useRemote = true
        },
    )
}

@Composable
private fun DocumentVideoBlock(
    taskId: String,
    asset: DocumentAsset,
    output: TaskOutput?,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(asset.alt.ifBlank { "文档内视频" }, style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = {
                    if (output != null) {
                        viewModel.openDocumentMedia(context, taskId, output)
                    } else {
                        val remote = asset.variants.firstOrNull()?.urls?.firstOrNull()
                        if (remote != null) openExternalUrl(context, remote, viewModel)
                        else viewModel.showMessage("视频文件不可用")
                    }
                },
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("播放视频")
            }
        }
    }
}

@Composable
private fun DocumentImageViewer(
    taskId: String,
    images: List<DocumentReaderImage>,
    initialPage: Int,
    imageLoader: ImageLoader,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, images.lastIndex),
        pageCount = images::size,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { page -> images[page].asset.id },
                ) { page ->
                    DocumentImageContent(
                        taskId = taskId,
                        image = images[page],
                        imageLoader = imageLoader,
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 56.dp),
                        contentDescription = "第 ${page + 1} 张图片，共 ${images.size} 张",
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭图片预览", tint = Color.White)
                    }
                    Text(
                        "${pagerState.currentPage + 1} / ${images.size}",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}

private fun documentDisplayText(value: String): String = value
    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
    .replace(Regex("~~(.+?)~~"), "$1")
    .replace(Regex("(?<!\\*)\\*([^*]+?)\\*"), "$1")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "$1 ($2)")
    .replace("\\*", "*")
    .replace("\\_", "_")
    .replace("\\[", "[")
    .replace("\\]", "]")

private fun openExternalUrl(context: Context, value: String, viewModel: MainViewModel) {
    val uri = runCatching { Uri.parse(value) }.getOrNull()
    if (uri?.scheme !in setOf("http", "https")) {
        viewModel.showMessage("链接无效")
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { viewModel.showMessage("没有可打开该链接的应用") }
}
