package com.local.douyindownloader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZhihuQuestionArchiveScreen(
    viewModel: ZhihuQuestionArchiveViewModel,
    onBack: () -> Unit,
    onOpenChildTask: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<ZhihuQuestionAnswer?>(null) }
    val closeScreen = {
        viewModel.close()
        onBack()
    }
    BackHandler(onBack = closeScreen)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.archive?.title ?: state.parentTask?.title ?: "知乎问题归档",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = closeScreen) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingArchive(innerPadding)
            state.parentTask == null -> ArchiveError(innerPadding, state.error)
            else -> ArchiveContent(
                state = state,
                innerPadding = innerPadding,
                onPreviousPage = viewModel::previousPage,
                onNextPage = viewModel::nextPage,
                onResume = viewModel::resume,
                onContinue = viewModel::continueNextPage,
                onResumeAnswer = viewModel::resumeAnswer,
                onCancel = viewModel::cancel,
                onOpenChildTask = onOpenChildTask,
                onDeleteAnswer = { deleteTarget = it },
                onListPositionChanged = viewModel::rememberListPosition,
            )
        }
    }
    deleteTarget?.let { answer ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条回答？") },
            text = { Text("将删除回答任务、已下载的正文、评论和媒体文件，并从该问题归档中移除。") },
            confirmButton = {
                Button(onClick = {
                    deleteTarget = null
                    viewModel.deleteAnswer(answer)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LoadingArchive(innerPadding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在读取问题归档…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArchiveError(innerPadding: PaddingValues, error: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(error.ifBlank { "无法读取问题归档" }, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ArchiveContent(
    state: ZhihuQuestionArchiveUiState,
    innerPadding: PaddingValues,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onResume: () -> Unit,
    onContinue: () -> Unit,
    onResumeAnswer: (ZhihuQuestionAnswer) -> Unit,
    onCancel: () -> Unit,
    onOpenChildTask: (String) -> Unit,
    onDeleteAnswer: (ZhihuQuestionAnswer) -> Unit,
    onListPositionChanged: (Int, Int) -> Unit,
) {
    val archive = state.archive
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.listIndex,
        initialFirstVisibleItemScrollOffset = state.listOffset,
    )
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (index, offset) ->
            onListPositionChanged(index, offset)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "summary") {
            ArchiveSummary(
                state = state,
                onResume = onResume,
                onContinue = onContinue,
                onCancel = onCancel,
            )
        }
        item(key = "divider") { HorizontalDivider() }
        if (state.error.isNotBlank()) {
            item(key = "error") {
                Text(state.error, color = MaterialTheme.colorScheme.error)
            }
        }
        if (state.visibleAnswers.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (archive?.hasMore == true) "回答正在准备中…" else "当前没有已发现的回答",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(
                items = state.visibleAnswers,
                key = ZhihuQuestionAnswer::answerId,
            ) { answer ->
                SwipeRevealAnswerCard(
                    answer = answer,
                    childTask = state.childTasks[answer.answerId],
                    deleteEnabled = !state.actionInProgress && state.archive?.status !in setOf(
                        ZhihuQuestionStatus.QUEUED,
                        ZhihuQuestionStatus.RUNNING,
                    ),
                    onResume = { onResumeAnswer(answer) },
                    onOpenTask = onOpenChildTask,
                    onDelete = { onDeleteAnswer(answer) },
                )
            }
        }
        item(key = "pagination") {
            Pagination(
                pageNumber = state.pageNumber,
                totalPages = state.totalPages,
                hasPrevious = state.hasPreviousPage,
                hasNext = state.hasNextPage,
                onPreviousPage = {
                    onPreviousPage()
                    coroutineScope.launch { listState.scrollToItem(0) }
                },
                onNextPage = {
                    onNextPage()
                    coroutineScope.launch { listState.scrollToItem(0) }
                },
            )
        }
    }
}

@Composable
private fun ArchiveSummary(
    state: ZhihuQuestionArchiveUiState,
    onResume: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val archive = state.archive ?: return
    val canContinue = archive.status !in setOf(
        ZhihuQuestionStatus.QUEUED,
        ZhihuQuestionStatus.RUNNING,
    ) && canContinueQuestionArchive(archive, state.answers.size)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            archive.title.ifBlank { "知乎问题" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(questionStatusLabel(archive.status))
            Text(
                "已发现 ${state.answers.size} / ${archive.answerCount.coerceAtLeast(state.answers.size)} 条回答",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (archive.includeComments) {
            Text(
                "已开启评论归档",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (archive.error.isNotBlank()) {
            Text(archive.error, color = MaterialTheme.colorScheme.error)
        }
        if (archive.status == ZhihuQuestionStatus.PARTIAL) {
            Text(
                "部分回答未完成，可在对应回答卡片中单独重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.message.isNotBlank()) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canContinue) {
                Button(onClick = onContinue, enabled = !state.actionInProgress) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("继续下载下一批")
                }
            }
            if (archive.status in setOf(
                    ZhihuQuestionStatus.PAUSED,
                    ZhihuQuestionStatus.FAILED,
                    ZhihuQuestionStatus.CANCELLED,
                )
            ) {
                Button(onClick = onResume, enabled = !state.actionInProgress) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("继续归档")
                }
            }
            if (archive.status in setOf(
                    ZhihuQuestionStatus.QUEUED,
                    ZhihuQuestionStatus.RUNNING,
                )
            ) {
                OutlinedButton(onClick = onCancel, enabled = !state.actionInProgress) {
                    Icon(Icons.Default.Cancel, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("取消")
                }
            }
        }
        if (state.actionInProgress) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun SwipeRevealAnswerCard(
    answer: ZhihuQuestionAnswer,
    childTask: TaskRecord?,
    deleteEnabled: Boolean,
    onResume: () -> Unit,
    onOpenTask: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val revealWidth = 88.dp
    val density = LocalDensity.current
    val revealWidthPx = with(density) { revealWidth.toPx() }
    var dragOffset by remember(answer.answerId) { mutableFloatStateOf(0f) }
    var cardHeightPx by remember(answer.answerId) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxWidth()) {
        if (deleteEnabled) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(with(density) { cardHeightPx.toDp() }),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    modifier = Modifier
                        .width(revealWidth)
                        .fillMaxHeight()
                        .clickable(onClick = onDelete),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "删除",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
        AnswerCard(
            answer = answer,
            childTask = childTask,
            onResume = onResume,
            onOpenTask = onOpenTask,
            modifier = Modifier
                .onSizeChanged { cardHeightPx = it.height }
                .offset { IntOffset(dragOffset.roundToInt(), 0) }
                .then(
                    if (deleteEnabled) {
                        Modifier.pointerInput(answer.answerId, revealWidthPx) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset = (dragOffset + amount).coerceIn(-revealWidthPx, 0f)
                                },
                                onDragEnd = {
                                    dragOffset = if (dragOffset <= -revealWidthPx * 0.35f) {
                                        -revealWidthPx
                                    } else {
                                        0f
                                    }
                                },
                                onDragCancel = { dragOffset = 0f },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
private fun AnswerCard(
    answer: ZhihuQuestionAnswer,
    childTask: TaskRecord?,
    onResume: () -> Unit,
    onOpenTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val localState = childTask?.let(::questionLocalStateLabel) ?: "远端作品"
    val isActionable = answer.status in setOf(
        ZhihuQuestionAnswerStatus.FAILED,
        ZhihuQuestionAnswerStatus.PAUSED,
    ) || childTask?.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (answer.status == ZhihuQuestionAnswerStatus.FAILED) 0.78f else 1f)
            .then(
                if (childTask != null) Modifier.clickable { onOpenTask(childTask.id) }
                else Modifier
            ),
        colors = CardDefaults.outlinedCardColors(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    answer.excerpt.lineSequence().firstOrNull().orEmpty()
                        .ifBlank { "回答 #${answer.position}" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "#${answer.position}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "作者：${answer.author.ifBlank { "匿名用户" }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(answerStatusLabel(answer.status, childTask))
                Text(
                    localState,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (answer.commentCount > 0) {
                    Text(
                        "评论 ${answer.commentCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (isActionable) {
                TextButton(onClick = onResume) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("继续")
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Pagination(
    pageNumber: Int,
    totalPages: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onPreviousPage, enabled = hasPrevious) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上一页")
        }
        Text("第 $pageNumber / $totalPages 页")
        IconButton(onClick = onNextPage, enabled = hasNext) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下一页")
        }
    }
}

private fun questionStatusLabel(status: ZhihuQuestionStatus): String = when (status) {
    ZhihuQuestionStatus.QUEUED -> "等待归档"
    ZhihuQuestionStatus.RUNNING -> "归档中"
    ZhihuQuestionStatus.PAUSED -> "已暂停"
    ZhihuQuestionStatus.COMPLETE -> "已完成"
    ZhihuQuestionStatus.PARTIAL -> "部分完成"
    ZhihuQuestionStatus.FAILED -> "归档失败"
    ZhihuQuestionStatus.CANCELLED -> "已取消"
}

private fun answerStatusLabel(
    status: ZhihuQuestionAnswerStatus,
    childTask: TaskRecord?,
): String = when (childTask?.status) {
    TaskStatus.RUNNING -> "下载中"
    TaskStatus.QUEUED -> "等待下载"
    TaskStatus.COMPLETE -> "已完成"
    TaskStatus.FAILED -> "下载失败"
    TaskStatus.CANCELLED -> "已取消"
    TaskStatus.DELETING -> "正在删除"
    null -> when (status) {
        ZhihuQuestionAnswerStatus.DISCOVERED -> "已发现"
        ZhihuQuestionAnswerStatus.PREPARING -> "准备中"
        ZhihuQuestionAnswerStatus.QUEUED -> "等待下载"
        ZhihuQuestionAnswerStatus.COMPLETE -> "已完成"
        ZhihuQuestionAnswerStatus.FAILED -> "下载失败"
        ZhihuQuestionAnswerStatus.PAUSED -> "已暂停"
    }
    else -> childTask?.status?.wireValue.orEmpty()
}

private fun questionLocalStateLabel(task: TaskRecord): String = when {
    task.status == TaskStatus.RUNNING -> "本地下载中"
    task.status == TaskStatus.QUEUED -> "本地等待下载"
    task.status == TaskStatus.COMPLETE && task.fileState == FileState.AVAILABLE -> "本地已下载"
    task.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED) -> "本地下载失败"
    task.fileState == FileState.MISSING -> "本地文件已删除"
    else -> "远端作品"
}
