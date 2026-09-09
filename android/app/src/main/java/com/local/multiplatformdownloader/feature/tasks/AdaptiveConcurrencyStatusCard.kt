package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.download.AdaptiveDownloadState
import com.local.multiplatformdownloader.core.download.TemperatureKind
import com.local.multiplatformdownloader.core.download.TemperatureLevel
import com.local.multiplatformdownloader.core.download.formatByteSize
import com.local.multiplatformdownloader.core.download.temperatureLevel

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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun TaskHealthStatus(
    state: AdaptiveDownloadState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            contentDescription = "CPU占用率${formatPercent(state.cpuPercent)}，" +
                "CPU温度${formatTemperature(state.cpuTemperatureCelsius)}，" +
                "电池温度${formatTemperature(state.batteryTemperatureCelsius)}"
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "CPU ${formatPercent(state.cpuPercent)} ·",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        TemperatureText(
            label = "CPU温度",
            value = state.cpuTemperatureCelsius,
            kind = TemperatureKind.CPU,
        )
        Text(
            "·",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TemperatureText(
            label = "电池温度",
            value = state.batteryTemperatureCelsius,
            kind = TemperatureKind.BATTERY,
        )
    }
}

@Composable
internal fun PlatformRiskCooldownBanner(
    state: AdaptiveDownloadState,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = state.platformRiskUntil
        .filterValues { it > now }
        .toList()
        .sortedBy { it.second }
    LaunchedEffect(state.platformRiskUntil) {
        while (state.hasActiveCircuit(System.currentTimeMillis())) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
        now = System.currentTimeMillis()
    }
    if (active.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            active.forEach { (platform, until) ->
                val seconds = ((until - now + 999L) / 1_000L).coerceAtLeast(0L)
                Text(
                    "${platform.displayName}风控冷却 · 剩余 ${seconds / 60}:" +
                        (seconds % 60).toString().padStart(2, '0'),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("冷却期间不会发起新的解析请求", style = MaterialTheme.typography.labelSmall)
        }
    }
}

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
                    if (state.activeCount == 0 && state.waitingCount == 0) {
                        "当前无下载任务"
                    } else {
                        "自动并发 · ${state.activeCount} 个任务 · " +
                            "${formatByteSize(state.aggregateBytesPerSecond)}/s"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起调度详情" else "展开调度详情",
                )
            }
            if (state.activeCount > 0 || state.waitingCount > 0 || state.hasActiveCircuit(now)) {
                Text(
                    state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "应用 CPU ${formatPercent(state.cpuPercent)} ·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TemperatureText(
                    label = "CPU温度",
                    value = state.cpuTemperatureCelsius,
                    kind = TemperatureKind.CPU,
                    bodyStyle = true,
                )
                Text("·", style = MaterialTheme.typography.bodySmall)
                TemperatureText(
                    label = "电池温度",
                    value = state.batteryTemperatureCelsius,
                    kind = TemperatureKind.BATTERY,
                    bodyStyle = true,
                )
            }
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

private fun formatTemperature(value: Float?): String = value?.let {
    String.format(Locale.CHINA, "%.1f℃", it)
} ?: "--"

private fun formatSlowFrames(value: Float): String = if (value < 0f) {
    "暂无"
} else {
    String.format(Locale.CHINA, "%.0f%%", value)
}

@Composable
private fun TemperatureText(
    label: String,
    value: Float?,
    kind: TemperatureKind,
    bodyStyle: Boolean = false,
) {
    Text(
        "$label ${formatTemperature(value)}",
        style = if (bodyStyle) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
        color = temperatureColor(temperatureLevel(value, kind)),
        maxLines = 1,
    )
}

@Composable
private fun temperatureColor(level: TemperatureLevel): Color = when (level) {
    TemperatureLevel.NORMAL -> Color(0xFF2E7D32)
    TemperatureLevel.WARM -> Color(0xFFF9A825)
    TemperatureLevel.HOT -> Color(0xFFEF6C00)
    TemperatureLevel.CRITICAL -> MaterialTheme.colorScheme.error
    TemperatureLevel.UNKNOWN -> MaterialTheme.colorScheme.outline
}
