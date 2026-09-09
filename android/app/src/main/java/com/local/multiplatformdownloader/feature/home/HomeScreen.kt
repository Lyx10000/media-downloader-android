package com.local.multiplatformdownloader.feature.home

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryUiState
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryViewModel
import com.local.multiplatformdownloader.feature.creator.HomeInputMode
import com.local.multiplatformdownloader.feature.creator.creatorKey
import com.local.multiplatformdownloader.feature.creator.taskCreatorKey
import com.local.multiplatformdownloader.feature.tasks.isTaskQueueVisible
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
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
        is ParseUiState.Ready -> {
            val parsedCreatorKey = state.result.authorStableId.takeIf(String::isNotBlank)
                ?.let { creatorKey(state.result.platform, it) }.orEmpty()
            val authorAlreadySaved = creatorState.creators.any { it.key == parsedCreatorKey }
            val authorHasActiveTask = parsedCreatorKey.isNotBlank() && uiState.allTasks.any { task ->
                taskCreatorKey(task) == parsedCreatorKey && isTaskQueueVisible(task)
            }
            ResultScreen(
                result = state.result,
                selectedVariant = uiState.selectedVariant,
                selectedAttachmentVariants = uiState.selectedAttachmentVariants,
                selectedBilibiliCids = uiState.selectedBilibiliCids,
                onBilibiliParts = viewModel::selectBilibiliParts,
                selectedMode = uiState.selectedMode,
                onVariant = viewModel::selectVariant,
                onAttachmentVariant = viewModel::selectAttachmentVariant,
                onMode = viewModel::selectMode,
                authorAlreadySaved = authorAlreadySaved,
                canArchiveAuthor = !authorHasActiveTask,
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
        }
        is ParseUiState.Error -> ErrorScreen(state, viewModel::retryParse, viewModel::resetParse)
    }
}
