package com.local.multiplatformdownloader.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.local.multiplatformdownloader.core.model.DocumentAssetKind
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaAttachment
import com.local.multiplatformdownloader.core.model.MediaAttachmentKind
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionDownloadScope


@Composable
internal fun ResultScreen(
    result: ParseResult,
    selectedVariant: Int,
    selectedAttachmentVariants: Map<String, Int>,
    selectedBilibiliCids: Set<String>,
    onBilibiliParts: (Set<String>) -> Unit,
    selectedMode: DownloadMode,
    onVariant: (Int) -> Unit,
    onAttachmentVariant: (String, Int) -> Unit,
    onMode: (DownloadMode) -> Unit,
    authorAlreadySaved: Boolean,
    canArchiveAuthor: Boolean,
    onDownload: (Boolean) -> Unit,
    onDownloadQuestion: (ZhihuQuestionDownloadScope, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var questionScope by remember(result.contentId) {
        mutableStateOf(ZhihuQuestionDownloadScope.FIRST_PAGE)
    }
    var includeComments by remember(result.contentId) { mutableStateOf(true) }
    var archiveAuthor by remember(result.contentId) { mutableStateOf(false) }
    LaunchedEffect(result.contentId, authorAlreadySaved, canArchiveAuthor) {
        if (authorAlreadySaved || !canArchiveAuthor) archiveAuthor = false
    }
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
                if (authorAlreadySaved) {
                    Text(
                        "该作者已收藏，下载完成后将归入作者本地库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canArchiveAuthor) {
                                archiveAuthor = !archiveAuthor
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = archiveAuthor,
                            enabled = canArchiveAuthor,
                            onCheckedChange = { archiveAuthor = it },
                        )
                        Column {
                            Text("收藏作者并归入作者本地库")
                            Text(
                                if (canArchiveAuthor) {
                                    "关闭时作为独立作品保存"
                                } else {
                                    "该作者已有活动任务，完成或取消后才能收藏"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
internal fun ErrorScreen(state: ParseUiState.Error, retry: () -> Unit, back: () -> Unit) {
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
