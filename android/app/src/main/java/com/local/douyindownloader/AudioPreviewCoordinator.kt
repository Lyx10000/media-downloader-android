package com.local.douyindownloader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

internal enum class AudioPreviewStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    ERROR,
}

internal data class AudioPreviewState(
    val taskId: String? = null,
    val uri: String = "",
    val status: AudioPreviewStatus = AudioPreviewStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String = "",
)

@Singleton
class AudioPreviewCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: DiagnosticLogger,
) {
    private val _state = MutableStateFlow(AudioPreviewState())
    internal val state: StateFlow<AudioPreviewState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener(::onAudioFocusChanged, mainHandler)
        .build()
    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    fun toggle(taskId: String, uri: Uri) {
        checkMainThread()
        val current = _state.value
        if (current.taskId != taskId || current.uri != uri.toString()) {
            prepareAndPlay(taskId, uri)
            return
        }
        when (current.status) {
            AudioPreviewStatus.PLAYING -> pause("USER_PAUSED")
            AudioPreviewStatus.PAUSED -> startPreparedPlayer()
            AudioPreviewStatus.IDLE, AudioPreviewStatus.ERROR -> prepareAndPlay(taskId, uri)
            AudioPreviewStatus.PREPARING -> Unit
        }
    }

    fun seekTo(taskId: String, positionMs: Long) {
        checkMainThread()
        if (_state.value.taskId != taskId) return
        val duration = _state.value.durationMs
        if (duration <= 0L) return
        val target = positionMs.coerceIn(0L, duration)
        runCatching {
            player?.seekTo(target, MediaPlayer.SEEK_CLOSEST)
            _state.value = _state.value.copy(positionMs = target)
            log(taskId, "AUDIO_SEEK", JSONObject().put("position_ms", target))
        }.onFailure { fail(taskId, "seek", it) }
    }

    fun stopAndRelease(reason: String = "PAGE_HIDDEN") {
        checkMainThread()
        releasePlayer(reason, resetState = true)
    }

    fun stopIfTask(taskId: String, reason: String = "PREVIEW_COLLAPSED") {
        checkMainThread()
        if (_state.value.taskId == taskId) releasePlayer(reason, resetState = true)
    }

    private fun prepareAndPlay(taskId: String, uri: Uri) {
        releasePlayer("AUDIO_REPLACED", resetState = false)
        _state.value = AudioPreviewState(
            taskId = taskId,
            uri = uri.toString(),
            status = AudioPreviewStatus.PREPARING,
        )
        log(taskId, "AUDIO_PREPARE", JSONObject().put("authority", uri.authority.orEmpty()))
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(context, uri)
                setOnPreparedListener { prepared ->
                    if (_state.value.taskId != taskId) return@setOnPreparedListener
                    val duration = runCatching { prepared.duration.toLong() }.getOrDefault(0L)
                        .coerceAtLeast(0L)
                    _state.value = _state.value.copy(
                        status = AudioPreviewStatus.PAUSED,
                        durationMs = duration,
                        error = "",
                    )
                    log(taskId, "AUDIO_READY", JSONObject().put("duration_ms", duration))
                    startPreparedPlayer()
                }
                setOnCompletionListener {
                    stopProgressUpdates()
                    abandonAudioFocus()
                    _state.value = _state.value.copy(
                        status = AudioPreviewStatus.PAUSED,
                        positionMs = _state.value.durationMs,
                    )
                    log(taskId, "AUDIO_COMPLETED")
                }
                setOnErrorListener { _, what, extra ->
                    fail(taskId, "player", IllegalStateException("MediaPlayer $what/$extra"))
                    true
                }
                prepareAsync()
            }
        } catch (error: Throwable) {
            fail(taskId, "prepare", error)
        }
    }

    private fun startPreparedPlayer() {
        val current = _state.value
        val taskId = current.taskId ?: return
        val activePlayer = player ?: return
        val focusResult = runCatching { requestAudioFocus() }.getOrElse { error ->
            fail(taskId, "audio_focus", error)
            return
        }
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(taskId, "audio_focus", IllegalStateException("无法获取音频播放权限"))
            return
        }
        runCatching {
            if (current.durationMs > 0L && current.positionMs >= current.durationMs) {
                activePlayer.seekTo(0, MediaPlayer.SEEK_CLOSEST)
                _state.value = current.copy(positionMs = 0L)
            }
            activePlayer.start()
            _state.value = _state.value.copy(status = AudioPreviewStatus.PLAYING, error = "")
            startProgressUpdates()
            log(taskId, "AUDIO_STARTED")
        }.onFailure { fail(taskId, "start", it) }
    }

    private fun pause(event: String) {
        val taskId = _state.value.taskId ?: return
        val activePlayer = player ?: return
        runCatching {
            if (activePlayer.isPlaying) activePlayer.pause()
            stopProgressUpdates()
            abandonAudioFocus()
            _state.value = _state.value.copy(
                status = AudioPreviewStatus.PAUSED,
                positionMs = runCatching { activePlayer.currentPosition.toLong() }
                    .getOrDefault(_state.value.positionMs),
            )
            log(taskId, event)
        }.onFailure { fail(taskId, "pause", it) }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (isActive && _state.value.status == AudioPreviewStatus.PLAYING) {
                val activePlayer = player ?: break
                val position = runCatching { activePlayer.currentPosition.toLong() }.getOrNull()
                    ?: break
                _state.value = _state.value.copy(positionMs = position.coerceAtLeast(0L))
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun releasePlayer(reason: String, resetState: Boolean) {
        val previousTask = _state.value.taskId
        stopProgressUpdates()
        player?.let { activePlayer ->
            runCatching { activePlayer.setOnPreparedListener(null) }
            runCatching { activePlayer.setOnCompletionListener(null) }
            runCatching { activePlayer.setOnErrorListener(null) }
            runCatching { activePlayer.release() }
        }
        player = null
        abandonAudioFocus()
        if (previousTask != null) log(previousTask, "AUDIO_RELEASED", JSONObject().put("reason", reason))
        if (resetState) _state.value = AudioPreviewState()
    }

    private fun fail(taskId: String, phase: String, error: Throwable) {
        val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
        releasePlayer("AUDIO_ERROR", resetState = false)
        _state.value = AudioPreviewState(
            taskId = taskId,
            uri = _state.value.uri,
            status = AudioPreviewStatus.ERROR,
            error = message,
        )
        log(taskId, "AUDIO_FAILED", JSONObject().apply {
            put("phase", phase)
            put("error", error.javaClass.name)
            put("message", message)
        })
    }

    private fun onAudioFocusChanged(change: Int) {
        if (change < AudioManager.AUDIOFOCUS_GAIN &&
            _state.value.status == AudioPreviewStatus.PLAYING
        ) {
            pause("AUDIO_FOCUS_LOST")
        }
    }

    private fun requestAudioFocus(): Int = audioManager.requestAudioFocus(focusRequest)

    private fun abandonAudioFocus() {
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private fun log(taskId: String, name: String, details: JSONObject = JSONObject()) {
        runCatching { logger.event(taskId, "PREVIEW_AUDIO", name, details) }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Audio preview must run on main thread" }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
