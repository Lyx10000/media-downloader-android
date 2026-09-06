package com.local.douyindownloader

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis

private data class PendingTransfer(
    val mode: ManagedTransferMode,
    val outputUris: Set<String>,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun TaskFileManagerScreen(
    task: TaskRecord,
    viewModel: MainViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenDocument: (TaskOutput) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val operationTaskId by viewModel.fileOperationTaskId.collectAsStateWithLifecycle()
    val operationInProgress = operationTaskId == task.id
    var managedFiles by remember(task.id) { mutableStateOf<List<ManagedFileItem>>(emptyList()) }
    var loading by remember(task.id) { mutableStateOf(true) }
    var selectedUris by remember(task.id) { mutableStateOf(emptySet<String>()) }
    var renameTarget by remember { mutableStateOf<ManagedFileItem?>(null) }
    var deleteRequested by remember { mutableStateOf(false) }
    var linkedMoveRequested by remember { mutableStateOf(false) }
    var pendingTransfer by remember { mutableStateOf<PendingTransfer?>(null) }
    val imageLoader = remember(context.applicationContext) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { destination ->
        val request = pendingTransfer
        pendingTransfer = null
        if (destination == null || request == null) return@rememberLauncherForActivityResult
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(destination, grantFlags)
        }.isSuccess && context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == destination && permission.isReadPermission && permission.isWritePermission
        }
        if (request.mode == ManagedTransferMode.MOVE && !persisted) {
            viewModel.showMessage("目标目录无法长期授权，只能复制，不能移动")
        } else {
            selectedUris = emptySet()
            viewModel.transferManagedFiles(
                task.id,
                request.outputUris,
                destination,
                request.mode,
            )
        }
    }

    LaunchedEffect(task.id) { viewModel.onFileManagerOpened(task.id) }
    LaunchedEffect(task.outputs, operationInProgress) {
        loading = true
        managedFiles = viewModel.describeManagedFiles(task.outputs)
        selectedUris = selectedUris.intersect(
            managedFiles.filter(ManagedFileItem::available).map { it.output.uri }.toSet(),
        )
        loading = false
    }
    DisposableEffect(imageLoader) { onDispose(imageLoader::shutdown) }

    val availableUris = managedFiles.filter(ManagedFileItem::available)
        .map { it.output.uri }
        .toSet()
    val selectedFiles = managedFiles.filter { it.output.uri in selectedUris && it.available }
    val selectionMode = selectedUris.isNotEmpty()

    fun leaveOrClearSelection() {
        if (selectionMode) selectedUris = emptySet() else onBack()
    }

    BackHandler(onBack = ::leaveOrClearSelection)

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) "已选择 ${selectedUris.size} 项" else "管理文件",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::leaveOrClearSelection) {
                        Icon(
                            if (selectionMode) Icons.Default.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (selectionMode) "退出选择" else "返回",
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                selectedUris = if (selectedUris == availableUris) {
                                    emptySet()
                                } else {
                                    availableUris
                                }
                            },
                        ) {
                            Text(if (selectedUris == availableUris) "取消全选" else "全选")
                        }
                    } else {
                        IconButton(onClick = { viewModel.openTaskFolder(context, task) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "用系统文件管理器打开原目录")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selectionMode) {
                ManagedFileActionBar(
                    operationInProgress = operationInProgress,
                    renameEnabled = selectedFiles.size == 1,
                    onShare = {
                        val outputs = selectedFiles.map(ManagedFileItem::output)
                        if (!canShareManagedOutputs(outputs)) {
                            viewModel.showMessage("图片、视频和音频请分开分享")
                        } else {
                            val files = selectedFiles.map { item ->
                                ShareableFile(
                                    item.uri,
                                    item.output.displayName,
                                    mediaMimeType(item.output.displayName, item.output.mimeType),
                                    item.output.sizeBytes,
                                )
                            }
                            selectedUris = emptySet()
                            viewModel.shareTaskFiles(context, task.id, files)
                        }
                    },
                    onRename = { renameTarget = selectedFiles.singleOrNull() },
                    onCopy = {
                        pendingTransfer = PendingTransfer(ManagedTransferMode.COPY, selectedUris)
                        destinationPicker.launch(null)
                    },
                    onMove = {
                        if (selectedFiles.any { it.output.isLinkedDocumentMedia() }) {
                            linkedMoveRequested = true
                        } else {
                            pendingTransfer = PendingTransfer(ManagedTransferMode.MOVE, selectedUris)
                            destinationPicker.launch(null)
                        }
                    },
                    onDelete = { deleteRequested = true },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            if (loading || operationInProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(
                task.title,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "点击打开文件，长按后可以多选操作",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!loading && managedFiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有可管理的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(managedFiles, key = { it.output.uri }) { item ->
                        ManagedFileRow(
                            item = item,
                            imageLoader = imageLoader,
                            selected = item.output.uri in selectedUris,
                            selectionMode = selectionMode,
                            enabled = !operationInProgress,
                            onClick = {
                                when {
                                    !item.available -> viewModel.showMessage("文件已被删除")
                                    selectionMode -> selectedUris = selectedUris.toggle(item.output.uri)
                                    item.output.isMarkdownDocument() -> onOpenDocument(item.output)
                                    else -> viewModel.openManagedFile(context, task.id, item)
                                }
                            },
                            onLongClick = {
                                if (item.available && !operationInProgress) {
                                    selectedUris = selectedUris + item.output.uri
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameFileDialog(
            target = target,
            warnAboutDocumentLink = target.output.isLinkedDocumentMedia(),
            onDismiss = { renameTarget = null },
            onConfirm = { requestedBase ->
                renameTarget = null
                selectedUris = emptySet()
                viewModel.renameManagedFile(task.id, target.output.uri, requestedBase)
            },
        )
    }
    if (deleteRequested) {
        AlertDialog(
            onDismissRequest = { deleteRequested = false },
            title = { Text("删除所选文件？") },
            text = {
                Text(
                    if (selectedFiles.any { it.output.isLinkedDocumentMedia() }) {
                        "将永久删除 ${selectedFiles.size} 个文件，其中包含 Markdown 正文引用的媒体；删除后正文中的本地链接会失效。"
                    } else {
                        "将永久删除 ${selectedFiles.size} 个文件，删除后可通过任务重新下载。"
                    },
                )
            },
            confirmButton = {
                Button(onClick = {
                    val targets = selectedUris
                    deleteRequested = false
                    selectedUris = emptySet()
                    viewModel.deleteManagedFiles(task.id, targets)
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequested = false }) { Text("取消") }
            },
        )
    }
    if (linkedMoveRequested) {
        AlertDialog(
            onDismissRequest = { linkedMoveRequested = false },
            title = { Text("移动文档媒体？") },
            text = { Text("所选文件被 Markdown 正文通过相对路径引用，移动后正文中的本地链接会失效。") },
            confirmButton = {
                Button(onClick = {
                    linkedMoveRequested = false
                    pendingTransfer = PendingTransfer(ManagedTransferMode.MOVE, selectedUris)
                    destinationPicker.launch(null)
                }) { Text("继续移动") }
            },
            dismissButton = {
                TextButton(onClick = { linkedMoveRequested = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ManagedFileActionBar(
    operationInProgress: Boolean,
    renameEnabled: Boolean,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    BottomAppBar {
        ManagedFileAction("分享", Icons.Default.Share, !operationInProgress, onShare)
        ManagedFileAction("重命名", Icons.Default.DriveFileRenameOutline, renameEnabled && !operationInProgress, onRename)
        ManagedFileAction("复制", Icons.Default.ContentCopy, !operationInProgress, onCopy)
        ManagedFileAction("移动", Icons.AutoMirrored.Filled.DriveFileMove, !operationInProgress, onMove)
        ManagedFileAction("删除", Icons.Default.Delete, !operationInProgress, onDelete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.ManagedFileAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .alpha(if (enabled) 1f else 0.38f)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = null,
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ManagedFileRow(
    item: ManagedFileItem,
    imageLoader: ImageLoader,
    selected: Boolean,
    selectionMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.available) 1f else 0.5f)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = if (selectionMode) "切换选择" else "打开文件",
                onLongClickLabel = "选择文件",
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        leadingContent = { ManagedFileThumbnail(item, imageLoader) },
        headlineContent = {
            Text(
                item.output.displayName.ifBlank { "未命名文件" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val mime = mediaMimeType(item.output.displayName, item.output.mimeType)
            Text(
                if (item.available) {
                    "${mediaCategoryLabel(mediaCategory(mime))} · ${formatByteSize(item.output.sizeBytes)}"
                } else {
                    "文件已删除"
                },
            )
        },
        trailingContent = {
            if (selectionMode && item.available) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    enabled = enabled,
                )
            }
        },
    )
}

@Composable
private fun ManagedFileThumbnail(item: ManagedFileItem, imageLoader: ImageLoader) {
    val context = LocalContext.current
    val mime = mediaMimeType(item.output.displayName, item.output.mimeType)
    val category = mediaCategory(mime)
    Surface(
        modifier = Modifier
            .size(56.dp)
            .clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (item.available && category in setOf("image", "video")) {
            val model = remember(item.uri, category) {
                ImageRequest.Builder(context)
                    .data(item.uri)
                    .apply { if (category == "video") videoFrameMillis(1_000) }
                    .build()
            }
            SubcomposeAsyncImage(
                model = model,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
                error = { ManagedFileTypeIcon(category) },
            )
        } else {
            ManagedFileTypeIcon(category)
        }
    }
}

@Composable
private fun ManagedFileTypeIcon(category: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            when (category) {
                "image" -> Icons.Default.Image
                "video" -> Icons.Default.Movie
                "audio" -> Icons.Default.AudioFile
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenameFileDialog(
    target: ManagedFileItem,
    warnAboutDocumentLink: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val currentName = target.output.displayName
    val extension = currentName.substringAfterLast('.', "")
        .takeIf { currentName.substringBeforeLast('.', "").isNotBlank() }
        .orEmpty()
    val initialBase = if (extension.isBlank()) currentName else currentName.substringBeforeLast('.')
    var value by remember(target.output.uri) { mutableStateOf(initialBase) }
    val renamed = renamedDisplayName(currentName, value)
    val error = when {
        value.isBlank() -> "文件名不能为空"
        renamed == null -> "文件名不能包含 / 或反斜杠"
        renamed == currentName -> "请输入不同的文件名"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("文件名") },
                    suffix = extension.takeIf(String::isNotBlank)?.let { suffix ->
                        { Text(".$suffix") }
                    },
                    isError = error != null,
                    supportingText = {
                        Text(error ?: "扩展名将保持不变")
                    },
                )
                if (warnAboutDocumentLink) {
                    Text(
                        "此文件被 Markdown 正文引用，重命名后正文中的本地链接会失效。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }, enabled = error == null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun TaskOutput.isLinkedDocumentMedia(): Boolean = relativePath.startsWith("media/") &&
    "_cover." !in displayName

private fun TaskOutput.isMarkdownDocument(): Boolean =
    mediaMimeType(displayName, mimeType) == "text/markdown"
