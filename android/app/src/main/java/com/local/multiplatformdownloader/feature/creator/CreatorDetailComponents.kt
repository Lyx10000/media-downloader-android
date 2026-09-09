package com.local.multiplatformdownloader.feature.creator

import com.local.multiplatformdownloader.core.download.shouldShowDownloadProgress
import com.local.multiplatformdownloader.core.download.TaskTrackDownloadProgress
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.settings.BatchDownloadSettings
import com.local.multiplatformdownloader.core.settings.BatchVideoQuality
import com.local.multiplatformdownloader.feature.tasks.TrackDownloadProgressBars
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CreatorProfileHeader(
    profile: CreatorProfile,
    onStop: () -> Unit,
    onFollow: () -> Unit,
    actionsEnabled: Boolean,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = "${profile.nickname}头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlatformBrandBadge(profile.platform)
                        Spacer(Modifier.size(8.dp))
                        Text(profile.nickname, style = MaterialTheme.typography.titleLarge)
                    }
                    profile.accountId.takeIf(String::isNotBlank)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(creatorStatusLabel(profile), style = MaterialTheme.typography.bodySmall)
                }
            }
            profile.bio.takeIf(String::isNotBlank)?.let { Text(it) }
            profile.location.takeIf(String::isNotBlank)?.let {
                Text("IP 属地：$it", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.metrics.forEach { metric ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text("${metric.label} ${metric.value}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            Text(
                if (profile.refreshedAt > 0L) {
                    "上次更新：${formatCreatorTime(profile.refreshedAt)}"
                } else "尚未刷新完整主页资料",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.archived) {
                    Button(onClick = onFollow, enabled = actionsEnabled) { Text("重新关注") }
                }
                TextButton(onClick = onStop, enabled = actionsEnabled) {
                    Text(if (profile.archived) "删除归档" else "停止关注")
                }
            }
        }
    }
}

@Composable
internal fun CreatorWorkCard(
    work: CreatorWork,
    selected: Boolean,
    selectionEnabled: Boolean,
    onToggle: () -> Unit,
    onManage: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    showLocalProgress: Boolean = false,
    trackProgress: TaskTrackDownloadProgress? = null,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().then(if (selectionEnabled) Modifier.clickable(onClick = onToggle) else Modifier),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionEnabled) Checkbox(checked = selected, onCheckedChange = { onToggle() })
            AsyncImage(
                model = work.coverUrl,
                contentDescription = "作品预览图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(76.dp).clip(MaterialTheme.shapes.medium),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(work.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    work.publishedAt.takeIf { it > 0L }
                        ?.let { "发布时间：${formatCreatorTime(it)}" }
                        ?: "发布时间未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mediaKindLabel(work.kind), style = MaterialTheme.typography.labelSmall)
                    Text(
                        localWorkStatusLabel(work.localStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (work.localStatus == CreatorWorkLocalStatus.AVAILABLE) {
                            MaterialTheme.colorScheme.primary
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (work.remoteStatus != CreatorWorkRemoteStatus.PUBLIC) {
                    Text(
                        remoteWorkStatusLabel(work.remoteStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (work.localStatus == CreatorWorkLocalStatus.AVAILABLE) {
                    Text("本地已有，当前页全选会跳过", style = MaterialTheme.typography.bodySmall)
                }
                if (showLocalProgress) {
                    val task = work.task
                    if (task != null && task.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
                        Text(task.stage, style = MaterialTheme.typography.bodySmall)
                        if (task.status == TaskStatus.RUNNING && trackProgress != null) {
                            TrackDownloadProgressBars(trackProgress)
                        } else if (task.status == TaskStatus.RUNNING && shouldShowDownloadProgress(task.stage)) {
                            LinearProgressIndicator(
                                progress = { task.progress.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else if (work.preparationFailed) {
                        Text(work.preparation?.error.orEmpty().ifBlank { "作品准备失败，可重试" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else if (task == null && work.preparation != null) {
                        Text(when (work.preparation.status) {
                            CreatorBatchWorkStatus.PAUSED -> "准备已暂停"
                            CreatorBatchWorkStatus.PARSING_HTTP, CreatorBatchWorkStatus.PARSING -> "正在准备作品"
                            else -> "等待作品准备"
                        },
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (onOpen != null || onManage != null || onRetry != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onOpen?.let { action ->
                            TextButton(onClick = action) { Text("预览") }
                        }
                        onManage?.let { action ->
                            TextButton(onClick = action) { Text("管理文件") }
                        }
                        onRetry?.let { action ->
                            TextButton(onClick = action) { Text("重试") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreatorPageControls(
    state: CreatorLibraryUiState,
    viewModel: CreatorLibraryViewModel,
) {
    val skipped = state.pageWorks.count(CreatorWork::shouldSkipInSelectAll)
    val pageSelected = currentCreatorPageSelected(state.selectedWorkKeys, state.pageWorks)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = viewModel::previousPage, enabled = state.hasPrevious && !state.isLoading) {
                Text("上一页")
            }
            Text("第 ${state.pageNumber} 页")
            OutlinedButton(onClick = viewModel::nextPage, enabled = state.hasMore && !state.isLoading) {
                Text("下一页")
            }
        }
        Button(
            onClick = viewModel::toggleCurrentPageSelection,
            enabled = state.selectedWorkKeys.isNotEmpty() || state.pageWorks.any { !it.shouldSkipInSelectAll },
        ) {
            Text(if (pageSelected) "取消全选" else "全选当前页")
        }
        Text(
            "本页 ${state.pageWorks.size} 个，已下载会跳过 $skipped 个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.selectedCreator?.platform == SourcePlatform.XIAOHONGSHU) {
            Text(
                "小红书当前展示网页首屏可稳定获取的公开作品；平台要求后续请求使用页面运行时签名，因此暂不提供不可靠的翻页入口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CreatorSelectionBar(
    state: CreatorLibraryUiState,
    viewModel: CreatorLibraryViewModel,
    onDownload: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("已选 ${state.selectedWorkKeys.size} 个", fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.selectedWorkKeys.isEmpty()) "选择作品后估算空间"
                    else "约 ${formatBytes(state.estimate.minimumBytes)}–${formatBytes(state.estimate.maximumBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.estimate.estimatedItems > 0) {
                    Text(
                        "${state.estimate.estimatedItems} 个作品按类型和时长粗略估算",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (state.availableBytes == null) "存储提供方未提供容量"
                    else "可用 ${formatBytes(state.availableBytes)} / 总计 ${formatBytes(state.totalBytes ?: 0L)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.availableBytes != null &&
                    state.estimate.maximumBytes >= state.availableBytes
                ) {
                    Text(
                        "估算上限可能超过剩余空间，请减少选择或更换保存位置",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Button(
                onClick = onDownload,
                enabled = state.selectedWorkKeys.isNotEmpty() && !state.isStartingBatch,
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("下载")
            }
        }
    }
}

@Composable
internal fun BatchSettingsDialog(
    state: CreatorLibraryUiState,
    onSettings: (BatchDownloadSettings) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val settings = state.batchSettings
    val selectedKinds = state.allWorks.asSequence()
        .filter { it.key in state.selectedWorkKeys }
        .map(CreatorWork::kind)
        .toSet()
    val hasVideos = MediaKind.VIDEO in selectedKinds
    val hasImages = MediaKind.IMAGE in selectedKinds
    val hasDocuments = MediaKind.DOCUMENT in selectedKinds
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本批次下载设置") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.selectedCreator?.platform == SourcePlatform.BILIBILI) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(settings.bilibiliAllParts, { onSettings(settings.copy(bilibiliAllParts = it)) })
                            Text("多P稿件下载全部P（关闭则仅P1）")
                        }
                        Text("每个稿件显示一张卡，各P独立保存；实际下载数量可能多于勾选的稿件数。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.selectedCreator?.platform == SourcePlatform.XIAOHONGSHU) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(
                                "部分小红书作品可能需要页面环境准备。准备期间请保持应用在前台；" +
                                    "切到后台、锁屏或离开作者页面会暂停，返回后可以继续。",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (hasVideos) {
                    item { Text("视频清晰度", fontWeight = FontWeight.SemiBold) }
                    items(BatchVideoQuality.entries) { quality ->
                        SettingRadioRow(
                            selected = settings.quality == quality,
                            label = when (quality) {
                                BatchVideoQuality.HIGHEST -> "最高可用档位"
                                BatchVideoQuality.UP_TO_1080P -> "最高不超过 1080p"
                                BatchVideoQuality.UP_TO_720P -> "最高不超过 720p"
                            },
                            onClick = { onSettings(settings.copy(quality = quality)) },
                        )
                    }
                    item {
                        Text(
                            "音视频处理",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(
                        listOf(
                            DownloadMode.MERGE_KEEP,
                            DownloadMode.MP4_ONLY,
                            DownloadMode.TRACKS,
                            DownloadMode.VIDEO_ONLY,
                            DownloadMode.AUDIO_ONLY,
                        ),
                    ) { mode ->
                        SettingRadioRow(
                            selected = settings.mode == mode,
                            label = when (mode) {
                                DownloadMode.MERGE_KEEP -> "合成并保留原视频和音频"
                                DownloadMode.MP4_ONLY -> "仅保留成品 MP4"
                                DownloadMode.TRACKS -> "分别下载视频和音频"
                                DownloadMode.VIDEO_ONLY -> "仅视频"
                                DownloadMode.AUDIO_ONLY -> "仅音频"
                            },
                            onClick = { onSettings(settings.copy(mode = mode)) },
                        )
                    }
                }
                if (hasImages) {
                    item {
                        BatchFixedPolicy(
                            title = "图片/图文",
                            description = "下载全部最高可用图片；实况照片和背景音乐存在时一并保留。",
                        )
                    }
                }
                if (hasDocuments) {
                    item {
                        BatchFixedPolicy(
                            title = "知乎文章/回答",
                            description = "保存 Markdown，并下载正文图片和内嵌视频。",
                        )
                    }
                }
                if (hasVideos && selectedKinds.size > 1) {
                    item {
                        Text(
                            "上方清晰度和音视频选项只作用于视频作品。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (selectedKinds.isEmpty()) {
                    item {
                        Text(
                            "没有找到已选作品，请返回后重新选择。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = selectedKinds.isNotEmpty()) { Text("开始下载") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun CreatorBatchParsingStatus(preparation: CreatorBatchPreparation) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "正在读取小红书作品 " +
                "${(preparation.processed + 1).coerceAtMost(preparation.total.coerceAtLeast(1))}/" +
                "${preparation.total}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "页面状态已取得，正在生成下载任务，请稍候。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun BatchFixedPolicy(title: String, description: String) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SettingRadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

internal fun creatorStatusLabel(profile: CreatorProfile): String = when {
    profile.archived -> "本地归档"
    profile.accountStatus == CreatorAccountStatus.PUBLIC -> "公开主页"
    profile.accountStatus == CreatorAccountStatus.RESTRICTED -> "主页受限或私密"
    profile.accountStatus == CreatorAccountStatus.DEACTIVATED -> "账号已注销"
    profile.accountStatus == CreatorAccountStatus.REFRESH_FAILED -> "刷新失败，显示缓存"
    profile.accountStatus == CreatorAccountStatus.INACCESSIBLE -> "暂不可访问"
    else -> "状态待确认"
}

internal fun mediaKindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.VIDEO -> "视频"
    MediaKind.IMAGE -> "图文"
    MediaKind.DOCUMENT -> "文章"
}

internal fun localWorkStatusLabel(status: CreatorWorkLocalStatus): String = when (status) {
    CreatorWorkLocalStatus.NOT_DOWNLOADED -> "未下载"
    CreatorWorkLocalStatus.QUEUED -> "等待下载"
    CreatorWorkLocalStatus.DOWNLOADING -> "下载中"
    CreatorWorkLocalStatus.AVAILABLE -> "已下载"
    CreatorWorkLocalStatus.PARTIAL -> "部分文件缺失"
    CreatorWorkLocalStatus.DELETED -> "本地已删除"
    CreatorWorkLocalStatus.FAILED -> "下载失败"
}

internal fun remoteWorkStatusLabel(status: CreatorWorkRemoteStatus): String = when (status) {
    CreatorWorkRemoteStatus.PUBLIC -> "公开可见"
    CreatorWorkRemoteStatus.NOT_DETECTED -> "本次公开页未探测到，本地记录仍保留"
    CreatorWorkRemoteStatus.UNAVAILABLE -> "作品已明确不可访问，本地文件仍保留"
    CreatorWorkRemoteStatus.CHECK_FAILED -> "远端检查失败，本地记录仍保留"
    CreatorWorkRemoteStatus.UNKNOWN -> "远端状态待确认"
}

internal fun formatCreatorTime(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(
        Date(if (value in 1..99_999_999_999L) value * 1_000L else value),
    )

internal fun formatBytes(value: Long): String = when {
    value <= 0L -> "0 MB"
    value >= 1024L * 1024L * 1024L -> "%.1f GB".format(Locale.US, value / (1024.0 * 1024.0 * 1024.0))
    else -> "%.1f MB".format(Locale.US, value / (1024.0 * 1024.0))
}
