package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.download.AdaptiveDownloadState
import com.local.multiplatformdownloader.core.download.formatByteSize

import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun AdaptiveConcurrencyStatusCard(state: AdaptiveDownloadState) {
    var expanded by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.platformRiskUntil) {
        while (state.hasActiveCircuit(now)) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
    }
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "自动并发 · ${state.activeCount} 个任务 · ${formatByteSize(state.aggregateBytesPerSecond)}/s",
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起调度详情" else "展开调度详情",
                )
            }
            Text(
                state.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "应用 CPU ${formatPercent(state.cpuPercent)} · 系统热状态 ${thermalLabel(state.thermalStatus)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.platformRiskUntil
                .filterValues { it > now }
                .toList()
                .sortedBy { it.second }
                .forEach { (platform, until) ->
                    val seconds = ((until - now + 999L) / 1_000L).coerceAtLeast(0L)
                    Text(
                        "${platform.displayName}风控冷却：${seconds / 60}:" +
                            (seconds % 60).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            if (expanded) {
                Text(
                    "当前 ${state.activeCount} / 目标 ${state.targetConcurrency}，等待 ${state.waitingCount}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "峰值 ${formatByteSize(state.peakBytesPerSecond)}/s · 慢帧 " +
                        formatSlowFrames(state.slowFramePercent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatPercent(value: Float): String = String.format(Locale.CHINA, "%.0f%%", value)

private fun formatSlowFrames(value: Float): String = if (value < 0f) {
    "暂无"
} else {
    String.format(Locale.CHINA, "%.0f%%", value)
}

private fun thermalLabel(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "正常"
    PowerManager.THERMAL_STATUS_LIGHT -> "轻微"
    PowerManager.THERMAL_STATUS_MODERATE -> "偏高"
    PowerManager.THERMAL_STATUS_SEVERE -> "较高"
    PowerManager.THERMAL_STATUS_CRITICAL -> "严重"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "即将关机"
    else -> "未知"
}
