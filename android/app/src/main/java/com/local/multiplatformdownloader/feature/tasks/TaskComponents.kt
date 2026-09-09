package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.download.formatByteSize
import com.local.multiplatformdownloader.core.download.formatOutputSummary
import com.local.multiplatformdownloader.core.download.shouldShowDownloadProgress
import com.local.multiplatformdownloader.core.model.FileState
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.feature.home.MainViewModel
import com.local.multiplatformdownloader.feature.preview.MediaPreviewState
import com.local.multiplatformdownloader.feature.preview.MediaPreviewStatus
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchive
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionStatus
import com.local.multiplatformdownloader.feature.zhihuarchive.canContinueQuestionArchive
import com.local.multiplatformdownloader.platform.bilibili.BilibiliTaskGroupCard
import com.local.multiplatformdownloader.platform.bilibili.BilibiliTaskGroupDialog
import com.local.multiplatformdownloader.platform.bilibili.bilibiliGroupKey
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch



@Composable
internal fun CreatorIdentityLine(
    label: String,
    value: String,
    copyDescription: String = "复制$label",
    onCopy: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label：$value",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        CreatorCopyButton(contentDescription = copyDescription, onClick = onCopy)
    }
}

@Composable
internal fun CreatorCopyButton(
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            Icons.Default.ContentCopy,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
        )
    }
}

internal fun copyCreatorText(context: android.content.Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private sealed interface TaskPreviewLoadState {
    data object Loading : TaskPreviewLoadState
    data object TextOnly : TaskPreviewLoadState
    data object Unavailable : TaskPreviewLoadState
    data class Ready(val media: List<TaskPreviewMedia>) : TaskPreviewLoadState
}

@Composable
internal fun TaskPreviewPanel(
    task: TaskRecord,
    mediaState: MediaPreviewState,
    imageLoader: ImageLoader,
    isFullscreen: Boolean,
    viewModel: MainViewModel,
) {
    var previewState by remember(task.id, task.outputs) {
        mutableStateOf<TaskPreviewLoadState>(TaskPreviewLoadState.Loading)
    }
    LaunchedEffect(task.id, task.outputs) {
        previewState = viewModel.resolveTaskPreview(task.id, task.outputs)
            .takeIf(List<TaskPreviewMedia>::isNotEmpty)
            ?.let(TaskPreviewLoadState::Ready)
            ?: if (isTextOnlyPreview(task.outputs)) {
                TaskPreviewLoadState.TextOnly
            } else {
                TaskPreviewLoadState.Unavailable
            }
    }
    DisposableEffect(task.id) {
        onDispose { viewModel.stopMediaPreviewIfTask(task.id) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (val state = previewState) {
            TaskPreviewLoadState.Loading -> PreviewLoading()
            TaskPreviewLoadState.TextOnly -> Text(
                "这是纯文本内容，没有可预览的图片、视频或音频",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            TaskPreviewLoadState.Unavailable -> Text(
                "无法读取预览，文件可能已被移动或删除",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            is TaskPreviewLoadState.Ready -> state.media.forEach { media ->
                when (media.kind) {
                    TaskPreviewKind.IMAGE -> ImagePreview(media = media, imageLoader = imageLoader)
                    TaskPreviewKind.VIDEO -> VideoPreview(
                        taskId = task.id,
                        media = media,
                        state = mediaState,
                        imageLoader = imageLoader,
                        isFullscreen = isFullscreen,
                        onToggle = { viewModel.toggleMediaPreview(task.id, media) },
                        onSeek = { viewModel.seekMediaPreview(task.id, it) },
                        onToggleMute = { viewModel.toggleMediaMute(task.id) },
                        onFullscreen = { viewModel.enterFullscreen(task.id, media) },
                    )
                    TaskPreviewKind.AUDIO -> MediaPreviewControls(
                        taskId = task.id,
                        media = media,
                        state = mediaState,
                        onToggle = { viewModel.toggleMediaPreview(task.id, media) },
                        onSeek = { viewModel.seekMediaPreview(task.id, it) },
                        onToggleMute = { viewModel.toggleMediaMute(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PreviewLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text("正在读取预览…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ImagePreview(
    media: TaskPreviewMedia,
    imageLoader: ImageLoader,
) {
    val context = LocalContext.current
    val images = remember(media.images, media.output, media.uri, media.mimeType) {
        media.images.ifEmpty {
            listOf(TaskPreviewImage(media.output, media.uri, media.mimeType))
        }
    }
    val pagerState = rememberPagerState(pageCount = images::size)
    val coroutineScope = rememberCoroutineScope()
    Text(
        if (images.size > 1) "图片预览 · 共 ${images.size} 张"
        else "图片预览",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        beyondViewportPageCount = 1,
        key = { page -> images[page].uri.toString() },
    ) { page ->
        val image = images[page]
        val model = remember(image.uri) {
            ImageRequest.Builder(context)
                .data(image.uri)
                .build()
        }
        SubcomposeAsyncImage(
            model = model,
            imageLoader = imageLoader,
            contentDescription = "第 ${page + 1} 张图片，共 ${images.size} 张",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = { PreviewLoading() },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("无法生成预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
    if (images.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                },
                enabled = pagerState.currentPage > 0,
            ) {
                Text("上一张")
            }
            Text(
                "${pagerState.currentPage + 1} / ${images.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                },
                enabled = pagerState.currentPage < images.lastIndex,
            ) {
                Text("下一张")
            }
        }
    }
}

@Composable
internal fun VideoPreview(
    taskId: String,
    media: TaskPreviewMedia,
    state: MediaPreviewState,
    imageLoader: ImageLoader,
    isFullscreen: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onFullscreen: () -> Unit,
) {
    val isCurrent = state.taskId == taskId && state.source.samePlaybackSource(media)
    val player = state.player.takeIf { isCurrent }
    val hasPlayer = player != null
    var inlineSurfaceReady by remember(player) { mutableStateOf(!isFullscreen) }
    var centerControlVisible by remember(player) { mutableStateOf(true) }
    var visibilityTimerVersion by remember(player) { mutableIntStateOf(0) }
    val revealCenterControl: () -> Unit = {
        centerControlVisible = true
        visibilityTimerVersion++
    }
    LaunchedEffect(isFullscreen, player) {
        if (isFullscreen) {
            inlineSurfaceReady = false
        } else if (player != null) {
            // Let the fullscreen dialog dispose its video Surface before the card attaches a new one.
            withFrameNanos { }
            inlineSurfaceReady = true
        }
    }
    LaunchedEffect(state.status, centerControlVisible, visibilityTimerVersion, isFullscreen) {
        when {
            isFullscreen -> Unit
            state.status in setOf(MediaPreviewStatus.ENDED, MediaPreviewStatus.PREPARING) -> {
                centerControlVisible = true
            }
            centerControlVisible -> {
                delay(VIDEO_CONTROLS_TIMEOUT_MS)
                centerControlVisible = false
            }
        }
    }
    Text(
        when (media.videoAudioMode) {
            VideoAudioMode.SEPARATE -> "视频预览 · 独立音轨同步播放"
            VideoAudioMode.SILENT -> "视频预览 · 无可用音轨"
            else -> "视频预览"
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(DEFAULT_VIDEO_ASPECT_RATIO)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            hasPlayer && !isFullscreen && inlineSurfaceReady -> {
                VideoPlayerSurface(player!!)
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(state.status, centerControlVisible) {
                            detectTapGestures {
                                when {
                                    state.status == MediaPreviewStatus.PREPARING -> Unit
                                    state.status == MediaPreviewStatus.ENDED -> revealCenterControl()
                                    centerControlVisible -> centerControlVisible = false
                                    else -> revealCenterControl()
                                }
                            }
                        },
                )
                if (state.status == MediaPreviewStatus.PREPARING) {
                    CircularProgressIndicator(color = Color.White)
                }
                AnimatedVisibility(
                    visible = centerControlVisible &&
                        state.status != MediaPreviewStatus.PREPARING,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    IconButton(
                        onClick = {
                            revealCenterControl()
                            onToggle()
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                Color.Black.copy(alpha = 0.46f),
                                MaterialTheme.shapes.extraLarge,
                            ),
                    ) {
                        Icon(
                            if (state.status == MediaPreviewStatus.PLAYING) Icons.Default.Pause
                            else if (state.status == MediaPreviewStatus.ENDED) Icons.Default.Replay
                            else Icons.Default.PlayArrow,
                            contentDescription = if (
                                state.status == MediaPreviewStatus.PLAYING
                            ) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
            }
            !hasPlayer -> VideoThumbnail(media, imageLoader, onToggle)
        }
    }
    if (hasPlayer) {
        MediaControlBar(
            state = state,
            onToggle = onToggle,
            onSeek = onSeek,
            onToggleMute = onToggleMute,
            onFullscreen = onFullscreen,
        )
    }
    PreviewMessages(isCurrent, state, "视频")
}

@Composable
internal fun VideoThumbnail(
    media: TaskPreviewMedia,
    imageLoader: ImageLoader,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    val model = remember(media.uri) {
        ImageRequest.Builder(context)
            .data(media.uri)
            .videoFrameMillis(1_000)
            .build()
    }
    SubcomposeAsyncImage(
        model = model,
        imageLoader = imageLoader,
        contentDescription = "视频缩略图",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
        loading = { PreviewLoading() },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无法生成缩略图", color = Color.White)
            }
        },
    )
    IconButton(
        onClick = onPlay,
        modifier = Modifier
            .size(64.dp)
            .background(Color.Black.copy(alpha = 0.46f), MaterialTheme.shapes.extraLarge),
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = "播放视频",
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoPlayerSurface(player: Player) {
    ContentFrame(
        player = player,
        modifier = Modifier.fillMaxSize(),
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun MediaPreviewControls(
    taskId: String,
    media: TaskPreviewMedia,
    state: MediaPreviewState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
) {
    val isCurrent = state.taskId == taskId && state.source.samePlaybackSource(media)
    val visibleState = if (isCurrent) state else MediaPreviewState()
    Text(
        "音频预览",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MediaControlBar(
        state = visibleState,
        onToggle = onToggle,
        onSeek = onSeek,
        onToggleMute = onToggleMute,
    )
    PreviewMessages(isCurrent, state, "音频")
}

@Composable
internal fun MediaControlBar(
    state: MediaPreviewState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
    onFullscreen: (() -> Unit)? = null,
    dark: Boolean = false,
    onInteraction: () -> Unit = {},
) {
    val durationMs = state.durationMs
    var sliderPosition by remember(state.taskId, state.source?.uri) { mutableFloatStateOf(0f) }
    var dragging by remember(state.taskId, state.source?.uri) { mutableStateOf(false) }
    LaunchedEffect(state.positionMs, dragging) {
        if (!dragging) sliderPosition = state.positionMs.toFloat()
    }
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    onInteraction()
                    onToggle()
                },
                enabled = state.status != MediaPreviewStatus.PREPARING,
            ) {
                if (state.status == MediaPreviewStatus.PREPARING) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        color = foreground,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        if (state.status == MediaPreviewStatus.PLAYING) Icons.Default.Pause
                        else if (state.status == MediaPreviewStatus.ENDED) Icons.Default.Replay
                        else Icons.Default.PlayArrow,
                        contentDescription = if (state.status == MediaPreviewStatus.PLAYING) "暂停" else "播放",
                        tint = foreground,
                    )
                }
            }
            Slider(
                value = sliderPosition.coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = {
                    onInteraction()
                    dragging = true
                    sliderPosition = it
                },
                onValueChangeFinished = {
                    onInteraction()
                    dragging = false
                    onSeek(sliderPosition.toLong())
                },
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                enabled = durationMs > 0L && state.status !in setOf(
                    MediaPreviewStatus.PREPARING,
                    MediaPreviewStatus.ERROR,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    onInteraction()
                    onToggleMute()
                },
                enabled = state.taskId != null,
            ) {
                Icon(
                    if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (state.isMuted) "取消静音" else "静音",
                    tint = foreground,
                )
            }
            onFullscreen?.let { openFullscreen ->
                IconButton(
                    onClick = {
                        onInteraction()
                        openFullscreen()
                    },
                    enabled = state.player != null,
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "全屏", tint = foreground)
                }
            }
        }
        Text(
            "${formatPlaybackTime(sliderPosition.toLong())} / ${formatPlaybackTime(durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (dark) Color.White.copy(alpha = 0.82f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Composable
internal fun PreviewMessages(isCurrent: Boolean, state: MediaPreviewState, mediaLabel: String) {
    if (isCurrent && state.message.isNotBlank()) {
        Text(
            state.message,
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (isCurrent && state.status == MediaPreviewStatus.ERROR) {
        Text(
            "${mediaLabel}预览失败：${state.error}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun FullscreenVideoPreview(
    state: MediaPreviewState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val player = state.player ?: return
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var visibilityTimerVersion by remember(player) { mutableIntStateOf(0) }
    val revealControls: () -> Unit = {
        controlsVisible = true
        visibilityTimerVersion++
    }
    LaunchedEffect(state.status, controlsVisible, visibilityTimerVersion) {
        when {
            state.status in setOf(MediaPreviewStatus.ENDED, MediaPreviewStatus.PREPARING) -> {
                controlsVisible = true
            }
            controlsVisible -> {
                delay(VIDEO_CONTROLS_TIMEOUT_MS)
                controlsVisible = false
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ContentFrame(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = ContentScale.Fit,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(state.status, controlsVisible) {
                            detectTapGestures {
                                when {
                                    state.status == MediaPreviewStatus.PREPARING -> Unit
                                    state.status == MediaPreviewStatus.ENDED -> revealControls()
                                    controlsVisible -> controlsVisible = false
                                    else -> revealControls()
                                }
                            }
                        },
                )
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(
                            onClick = {
                                revealControls()
                                onToggle()
                            },
                            enabled = state.status != MediaPreviewStatus.PREPARING,
                            modifier = Modifier
                                .size(72.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.46f),
                                    MaterialTheme.shapes.extraLarge,
                                ),
                        ) {
                            if (state.status == MediaPreviewStatus.PREPARING) {
                                CircularProgressIndicator(color = Color.White)
                            } else {
                                Icon(
                                    if (state.status == MediaPreviewStatus.PLAYING) Icons.Default.Pause
                                    else if (state.status == MediaPreviewStatus.ENDED) Icons.Default.Replay
                                    else Icons.Default.PlayArrow,
                                    contentDescription = if (
                                        state.status == MediaPreviewStatus.PLAYING
                                    ) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(42.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.46f),
                                    MaterialTheme.shapes.extraLarge,
                                ),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "退出全屏",
                                tint = Color.White,
                            )
                        }
                        MediaControlBar(
                            state = state,
                            onToggle = onToggle,
                            onSeek = onSeek,
                            onToggleMute = onToggleMute,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.62f))
                                .padding(bottom = 16.dp),
                            dark = true,
                            onInteraction = revealControls,
                        )
                    }
                }
            }
        }
    }
}

private const val VIDEO_CONTROLS_TIMEOUT_MS = 3_000L
private const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

internal fun TaskPreviewMedia?.samePlaybackSource(other: TaskPreviewMedia): Boolean =
    this?.uri == other.uri

internal fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(Locale.US, minutes, seconds)
}

internal data class PendingShare(
    val taskId: String,
    val files: List<ShareableFile>,
)
