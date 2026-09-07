package com.local.multiplatformdownloader.feature.preview


import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.feature.tasks.TaskPreviewMedia
import com.local.multiplatformdownloader.feature.tasks.VideoAudioMode

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal enum class MediaPreviewStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

internal data class MediaPreviewState(
    val taskId: String? = null,
    val source: TaskPreviewMedia? = null,
    val status: MediaPreviewStatus = MediaPreviewStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isMuted: Boolean = false,
    val videoAspectRatio: Float = 16f / 9f,
    val message: String = "",
    val error: String = "",
    val player: Player? = null,
)

@Singleton
@OptIn(UnstableApi::class)
class MediaPreviewCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: DiagnosticLogger,
) {
    private val _state = MutableStateFlow(MediaPreviewState())
    internal val state: StateFlow<MediaPreviewState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dataSourceFactory = DefaultDataSource.Factory(context)
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var fallbackAttempted = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val activePlayer = player ?: return
            val duration = activePlayer.duration.validDuration()
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.value = _state.value.copy(
                        status = MediaPreviewStatus.PREPARING,
                        durationMs = duration,
                    )
                }
                Player.STATE_READY -> {
                    _state.value = _state.value.copy(
                        status = if (activePlayer.isPlaying) {
                            MediaPreviewStatus.PLAYING
                        } else {
                            MediaPreviewStatus.PAUSED
                        },
                        durationMs = duration,
                        error = "",
                    )
                    log("MEDIA_READY", JSONObject().put("duration_ms", duration))
                }
                Player.STATE_ENDED -> {
                    stopProgressUpdates()
                    _state.value = _state.value.copy(
                        status = MediaPreviewStatus.ENDED,
                        positionMs = duration,
                        durationMs = duration,
                    )
                    log("MEDIA_COMPLETED")
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val current = _state.value
            if (current.taskId == null || current.status == MediaPreviewStatus.ERROR) return
            if (isPlaying) {
                _state.value = current.copy(status = MediaPreviewStatus.PLAYING)
                startProgressUpdates()
            } else if (current.status == MediaPreviewStatus.PLAYING) {
                stopProgressUpdates()
                updatePosition(MediaPreviewStatus.PAUSED)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height
            _state.value = _state.value.copy(videoAspectRatio = ratio.coerceIn(0.5f, 2.5f))
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = _state.value
            if (
                current.source?.videoAudioMode == VideoAudioMode.SEPARATE &&
                !fallbackAttempted
            ) {
                fallbackAttempted = true
                val position = player?.currentPosition?.coerceAtLeast(0L) ?: current.positionMs
                log("MERGED_SOURCE_FAILED", errorDetails(error))
                val silentSource = current.source.copy(
                    audioOutput = null,
                    audioUri = null,
                    videoAudioMode = VideoAudioMode.SILENT,
                )
                prepareAndPlay(
                    taskId = current.taskId ?: return,
                    source = silentSource,
                    startPositionMs = position,
                    fallbackMessage = "音频轨无法同步，已播放画面",
                    keepFallbackFlag = true,
                )
                return
            }
            fail("player", error)
        }
    }

    internal fun toggle(taskId: String, source: TaskPreviewMedia) {
        checkMainThread()
        val current = _state.value
        if (current.taskId != taskId || !current.source.samePlaybackSource(source)) {
            prepareAndPlay(taskId, source)
            return
        }
        when (current.status) {
            MediaPreviewStatus.PLAYING -> pause("USER_PAUSED")
            MediaPreviewStatus.PAUSED -> startPreparedPlayer()
            MediaPreviewStatus.ENDED -> {
                player?.seekTo(0L)
                _state.value = current.copy(positionMs = 0L)
                startPreparedPlayer()
            }
            MediaPreviewStatus.IDLE, MediaPreviewStatus.ERROR -> prepareAndPlay(taskId, source)
            MediaPreviewStatus.PREPARING -> Unit
        }
    }

    fun seekTo(taskId: String, positionMs: Long) {
        checkMainThread()
        if (_state.value.taskId != taskId) return
        val duration = _state.value.durationMs
        if (duration <= 0L) return
        val target = positionMs.coerceIn(0L, duration)
        runCatching {
            player?.seekTo(target)
            _state.value = _state.value.copy(
                positionMs = target,
                status = if (_state.value.status == MediaPreviewStatus.ENDED) {
                    MediaPreviewStatus.PAUSED
                } else {
                    _state.value.status
                },
            )
            log("MEDIA_SEEK", JSONObject().put("position_ms", target))
        }.onFailure { fail("seek", it) }
    }

    fun toggleMute(taskId: String) {
        checkMainThread()
        if (_state.value.taskId != taskId) return
        val muted = !_state.value.isMuted
        player?.volume = if (muted) 0f else 1f
        _state.value = _state.value.copy(isMuted = muted)
        log(if (muted) "MEDIA_MUTED" else "MEDIA_UNMUTED")
    }

    fun pause(reason: String = "PAGE_HIDDEN") {
        checkMainThread()
        val activePlayer = player ?: return
        val taskId = _state.value.taskId ?: return
        runCatching {
            activePlayer.pause()
            stopProgressUpdates()
            updatePosition(MediaPreviewStatus.PAUSED)
            log("MEDIA_PAUSED", JSONObject().put("reason", reason))
        }.onFailure { fail("pause", it, taskId) }
    }

    fun stopAndRelease(reason: String = "PAGE_HIDDEN") {
        checkMainThread()
        releasePlayer(reason, resetState = true)
    }

    fun stopIfTask(taskId: String, reason: String = "PREVIEW_COLLAPSED") {
        checkMainThread()
        if (_state.value.taskId == taskId) releasePlayer(reason, resetState = true)
    }

    private fun prepareAndPlay(
        taskId: String,
        source: TaskPreviewMedia,
        startPositionMs: Long = 0L,
        fallbackMessage: String = "",
        keepFallbackFlag: Boolean = false,
    ) {
        releasePlayer("MEDIA_REPLACED", resetState = false)
        if (!keepFallbackFlag) fallbackAttempted = false
        val newPlayer = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            addListener(listener)
            volume = if (_state.value.isMuted) 0f else 1f
        }
        player = newPlayer
        _state.value = MediaPreviewState(
            taskId = taskId,
            source = source,
            status = MediaPreviewStatus.PREPARING,
            positionMs = startPositionMs,
            isMuted = _state.value.isMuted,
            message = fallbackMessage,
            player = newPlayer,
        )
        log("MEDIA_PREPARE", JSONObject().apply {
            put("kind", source.kind.name.lowercase())
            put("video_audio_mode", source.videoAudioMode?.name?.lowercase() ?: "none")
        })
        runCatching {
            newPlayer.setMediaSource(buildMediaSource(source))
            if (startPositionMs > 0L) newPlayer.seekTo(startPositionMs)
            newPlayer.playWhenReady = true
            newPlayer.prepare()
        }.onFailure { fail("prepare", it, taskId) }
    }

    private fun startPreparedPlayer() {
        val activePlayer = player ?: return
        val taskId = _state.value.taskId ?: return
        runCatching {
            activePlayer.play()
            log("MEDIA_STARTED")
        }.onFailure { fail("start", it, taskId) }
    }

    private fun buildMediaSource(source: TaskPreviewMedia): MediaSource {
        val videoOrAudio = progressiveSource(source.uri)
        if (source.videoAudioMode != VideoAudioMode.SEPARATE) return videoOrAudio
        val audioUri = source.audioUri ?: return videoOrAudio
        return MergingMediaSource(
            true,
            true,
            videoOrAudio,
            progressiveSource(audioUri),
        )
    }

    private fun progressiveSource(uri: android.net.Uri): MediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(uri))

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive && _state.value.status == MediaPreviewStatus.PLAYING) {
                updatePosition(MediaPreviewStatus.PLAYING)
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun updatePosition(status: MediaPreviewStatus) {
        val activePlayer = player ?: return
        _state.value = _state.value.copy(
            status = status,
            positionMs = activePlayer.currentPosition.coerceAtLeast(0L),
            durationMs = activePlayer.duration.validDuration(),
        )
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun releasePlayer(reason: String, resetState: Boolean) {
        val previousTask = _state.value.taskId
        stopProgressUpdates()
        player?.let { activePlayer ->
            runCatching { activePlayer.removeListener(listener) }
            runCatching { activePlayer.release() }
        }
        player = null
        if (previousTask != null) {
            log("MEDIA_RELEASED", JSONObject().put("reason", reason), previousTask)
        }
        if (resetState) _state.value = MediaPreviewState()
    }

    private fun fail(phase: String, error: Throwable, fallbackTaskId: String? = null) {
        val previous = _state.value
        val taskId = previous.taskId ?: fallbackTaskId ?: return
        val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
        releasePlayer("MEDIA_ERROR", resetState = false)
        _state.value = previous.copy(
            taskId = taskId,
            status = MediaPreviewStatus.ERROR,
            error = message,
            player = null,
        )
        log("MEDIA_FAILED", errorDetails(error).put("phase", phase), taskId)
    }

    private fun errorDetails(error: Throwable) = JSONObject().apply {
        put("error", error.javaClass.name)
        put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
    }

    private fun log(
        name: String,
        details: JSONObject = JSONObject(),
        taskId: String? = _state.value.taskId,
    ) {
        taskId ?: return
        runCatching { logger.event(taskId, "PREVIEW_MEDIA", name, details) }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Media preview must run on main thread"
        }
    }

    private fun Long.validDuration(): Long = if (this == C.TIME_UNSET || this < 0L) 0L else this

    private fun TaskPreviewMedia?.samePlaybackSource(other: TaskPreviewMedia): Boolean =
        this?.uri == other.uri

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
