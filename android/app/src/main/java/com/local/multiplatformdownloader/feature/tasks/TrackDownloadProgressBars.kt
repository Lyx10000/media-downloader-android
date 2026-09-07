package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.download.DownloadTrackProgress
import com.local.multiplatformdownloader.core.download.TaskTrackDownloadProgress
import com.local.multiplatformdownloader.core.download.formatByteSize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun TrackDownloadProgressBars(value: TaskTrackDownloadProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        value.video?.let { TrackProgressBar("视频轨", it) }
        value.audio?.let { TrackProgressBar("音频轨", it) }
    }
}

@Composable
private fun TrackProgressBar(label: String, value: DownloadTrackProgress) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            trackProgressLabel(value),
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    value.fraction?.let { fraction ->
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

private fun trackProgressLabel(value: DownloadTrackProgress): String {
    val speed = "${formatByteSize(value.bytesPerSecond)}/s"
    return value.fraction?.let { fraction ->
        if (fraction >= 1f) "100% · 已完成" else "${(fraction * 100).toInt()}% · $speed"
    } ?: "已下载 ${formatByteSize(value.downloadedBytes)} · $speed"
}
