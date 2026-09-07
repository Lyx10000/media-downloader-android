package com.local.multiplatformdownloader.core.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DownloadTrackKind {
    VIDEO,
    AUDIO,
}

data class DownloadTrackProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let {
            (downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
        }
}

data class TaskTrackDownloadProgress(
    val video: DownloadTrackProgress? = null,
    val audio: DownloadTrackProgress? = null,
) {
    fun track(kind: DownloadTrackKind): DownloadTrackProgress? = when (kind) {
        DownloadTrackKind.VIDEO -> video
        DownloadTrackKind.AUDIO -> audio
    }

    fun withTrack(kind: DownloadTrackKind, value: DownloadTrackProgress): TaskTrackDownloadProgress =
        when (kind) {
            DownloadTrackKind.VIDEO -> copy(video = value)
            DownloadTrackKind.AUDIO -> copy(audio = value)
        }
}

internal fun combinedTrackProgress(value: TaskTrackDownloadProgress): Int {
    val tracks = listOfNotNull(value.video, value.audio)
    if (tracks.isEmpty()) return 0
    val fraction = tracks.sumOf { it.fraction?.toDouble() ?: 0.0 } / tracks.size
    return (fraction * 100).toInt().coerceIn(0, 100)
}

@Singleton
class TrackDownloadProgressRegistry @Inject constructor() {
    private val _state = MutableStateFlow<Map<String, TaskTrackDownloadProgress>>(emptyMap())
    val state: StateFlow<Map<String, TaskTrackDownloadProgress>> = _state.asStateFlow()

    fun start(
        taskId: String,
        videoTotalBytes: Long = 0L,
        audioTotalBytes: Long = 0L,
        includeVideo: Boolean = true,
        includeAudio: Boolean = true,
    ) {
        _state.update { current ->
            current + (taskId to TaskTrackDownloadProgress(
                video = DownloadTrackProgress(totalBytes = videoTotalBytes.coerceAtLeast(0L))
                    .takeIf { includeVideo },
                audio = DownloadTrackProgress(totalBytes = audioTotalBytes.coerceAtLeast(0L))
                    .takeIf { includeAudio },
            ))
        }
    }

    fun update(
        taskId: String,
        kind: DownloadTrackKind,
        downloadedBytes: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
    ): TaskTrackDownloadProgress {
        var updated = TaskTrackDownloadProgress()
        _state.update { current ->
            val task = current[taskId] ?: TaskTrackDownloadProgress()
            updated = task.withTrack(
                kind,
                DownloadTrackProgress(
                    downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                    totalBytes = totalBytes.coerceAtLeast(0L),
                    bytesPerSecond = bytesPerSecond.coerceAtLeast(0L),
                ),
            )
            current + (taskId to updated)
        }
        return updated
    }

    fun clear(taskId: String) {
        _state.update { it - taskId }
    }
}
