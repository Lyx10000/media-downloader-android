package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.model.SourcePlatform

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlatformFilterBar(
    selected: SourcePlatform?,
    onSelected: (SourcePlatform?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(
        modifier.fillMaxWidth().clickable { showSheet = true },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Tune, contentDescription = null)
            Column {
                Text("筛选", style = MaterialTheme.typography.titleSmall)
                Text(
                    selected?.displayName ?: "全部平台",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("筛选平台", style = MaterialTheme.typography.titleLarge)
                PlatformFilterOptions(selected = selected, onSelected = onSelected)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onSelected(null) }) { Text("重置") }
                    Button(onClick = { showSheet = false }) { Text("完成") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlatformFilterOptions(
    selected: SourcePlatform?,
    onSelected: (SourcePlatform?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelected(null) },
            label = { Text("全部平台") },
        )
        SourcePlatform.entries.forEach { platform ->
            FilterChip(
                selected = selected == platform,
                onClick = { onSelected(platform) },
                label = { Text(platform.displayName) },
            )
        }
    }
}
