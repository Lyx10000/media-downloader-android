package com.local.multiplatformdownloader.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.feature.creator.CREATOR_BATCH_PLATFORMS
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryUiState
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryViewModel
import com.local.multiplatformdownloader.platform.common.PlatformBrandBadge
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CreatorSearchContent(
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
                    Button(
                        enabled = state.organizingCreatorKey.isBlank(),
                        onClick = { viewModel.confirmCreator(onShowTasks) },
                    ) { Text(if (state.organizingCreatorKey == profile.key) "正在整理" else "加入") }
                }
            }
        }
    }
}

@Composable
internal fun PlatformCredentialCard(
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
internal fun ParsingStatus(text: String) {
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
