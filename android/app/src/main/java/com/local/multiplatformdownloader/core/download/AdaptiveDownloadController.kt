package com.local.multiplatformdownloader.core.download

import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.settings.SettingsRepository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class AdaptiveDownloadState(
    val activeCount: Int = 0,
    val targetConcurrency: Int = 1,
    val waitingCount: Int = 0,
    val aggregateBytesPerSecond: Long = 0L,
    val taskBytesPerSecond: Map<String, Long> = emptyMap(),
    val peakBytesPerSecond: Long = 0L,
    val cpuPercent: Float = 0f,
    val slowFramePercent: Float = -1f,
    val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    val reason: String = "从单任务开始评估",
    val platformRiskUntil: Map<SourcePlatform, Long> = emptyMap(),
) {
    fun hasActiveCircuit(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        platformRiskUntil.any { (_, until) -> until > nowEpochMs }

    fun shouldDisplay(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        activeCount > 0 || waitingCount > 0 || hasActiveCircuit(nowEpochMs)
}

/**
 * Process-local adaptive gate for whole download tasks. Bilibili's parallel audio/video
 * tracks still occupy one task slot; their transfer speeds are summed by channel.
 */
@Singleton
class AdaptiveDownloadController @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
    private val logger: DiagnosticLogger,
) {
    private data class TransferSample(
        val taskId: String,
        val bytesPerSecond: Long,
        val recordedAtMs: Long,
    )

    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transferSamples = ConcurrentHashMap<String, TransferSample>()
    private val frameCount = AtomicLong(0L)
    private val slowFrameCount = AtomicLong(0L)
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)
    private val _state = MutableStateFlow(AdaptiveDownloadState())
    val state: StateFlow<AdaptiveDownloadState> = _state.asStateFlow()
    private val riskLoaded = CompletableDeferred<Unit>()
    private val riskMutex = Mutex()
    private val taskGate = AdaptiveTaskGate<SourcePlatform>(
        initialTarget = MIN_CONCURRENCY,
        // This gate controls transfer concurrency only. Platform risk cooldowns protect
        // parsing/detail requests before a task is scheduled; once CDN URLs have been
        // resolved, blocking the transfer here leaves a valid task stuck at "准备下载".
        isBlocked = { false },
        onCountsChanged = { active, waiting ->
            _state.update { it.copy(activeCount = active, waitingCount = waiting) }
        },
    )

    @Volatile
    private var targetConcurrency = MIN_CONCURRENCY

    @Volatile
    private var riskUntil: Map<SourcePlatform, Long> = emptyMap()

    private val recentRiskAt = ConcurrentHashMap<SourcePlatform, Long>()
    private var policyState = AdaptivePolicyState()
    private var previousCpuMs = Process.getElapsedCpuTime()
    private var previousWallMs = SystemClock.elapsedRealtime()
    private var previousNetworkKey: String? = null
    private var ewmaSpeed = 0.0
    private var lastDecisionAtMs = 0L
    private var decisionCpuSum = 0f
    private var decisionCpuSamples = 0
    private var decisionFrameCount = 0L
    private var decisionSlowFrameCount = 0L
    private var decisionThermalMax = PowerManager.THERMAL_STATUS_NONE

    init {
        scope.launch {
            settingsRepository.settings.collectLatest { settings ->
                val now = System.currentTimeMillis()
                val current = settings.platformRiskUntil.filterValues { it > now }
                riskMutex.withLock {
                    riskUntil = current
                    _state.update { it.copy(platformRiskUntil = current) }
                }
                if (!riskLoaded.isCompleted) riskLoaded.complete(Unit)
                taskGate.dispatchEligible()
            }
        }
        scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                try {
                    sampleAndAdjust()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    targetConcurrency = MIN_CONCURRENCY
                    policyState = AdaptivePolicyState(reason = "性能采样异常，已使用单任务保护模式")
                    taskGate.setTarget(MIN_CONCURRENCY)
                    _state.update {
                        it.copy(targetConcurrency = MIN_CONCURRENCY, reason = policyState.reason)
                    }
                    logger.event(LOG_TASK_ID, "SCHEDULER", "ADAPTIVE_SAMPLE_FAILED", JSONObject().apply {
                        put("type", error.javaClass.name)
                        put("message", error.message.orEmpty())
                    })
                }
            }
        }
    }

    suspend fun <T> withTaskPermit(
        taskId: String,
        platform: SourcePlatform,
        block: suspend () -> T,
    ): T {
        val lease = taskGate.acquire(taskId, platform)
        return try {
            block()
        } finally {
            transferSamples.keys.removeIf { it.startsWith("${lease.token}:") }
            _state.update { it.copy(taskBytesPerSecond = it.taskBytesPerSecond - taskId) }
            taskGate.release(lease)
        }
    }

    fun recordTransfer(taskId: String, channel: String, bytesPerSecond: Long) {
        val token = taskGate.tokenForTask(taskId) ?: return
        transferSamples["$token:$channel"] = TransferSample(
            taskId = taskId,
            bytesPerSecond = bytesPerSecond.coerceAtLeast(0L),
            recordedAtMs = SystemClock.elapsedRealtime(),
        )
    }

    fun recordFrame(totalDurationNanos: Long) {
        if (_state.value.activeCount <= 0) return
        frameCount.incrementAndGet()
        if (totalDurationNanos >= SLOW_FRAME_NANOS) slowFrameCount.incrementAndGet()
    }

    fun isPlatformBlocked(platform: SourcePlatform, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        (riskUntil[platform] ?: 0L) > nowEpochMs

    suspend fun isPlatformBlockedAfterLoad(platform: SourcePlatform): Boolean {
        riskLoaded.await()
        return isPlatformBlocked(platform)
    }

    fun reportPlatformRisk(
        platform: SourcePlatform,
        code: String,
        httpStatuses: Collection<Int> = emptyList(),
    ) {
        if (code !in RISK_CODES && httpStatuses.none { it in RISK_HTTP_STATUSES }) return
        scope.launch {
            riskLoaded.await()
            riskMutex.withLock {
                val now = System.currentTimeMillis()
                val repeated = isPlatformBlocked(platform, now) ||
                    now - (recentRiskAt[platform] ?: Long.MIN_VALUE) <= REPEATED_RISK_WINDOW_MS
                recentRiskAt[platform] = now
                val until = now + if (repeated) REPEATED_RISK_COOLDOWN_MS else INITIAL_RISK_COOLDOWN_MS
                val updated = riskUntil.toMutableMap().apply { put(platform, until) }
                riskUntil = updated
                settingsRepository.setPlatformRiskUntil(updated)
                _state.update {
                    it.copy(
                        platformRiskUntil = updated,
                        reason = "${platform.displayName}触发风控，已暂停该平台新任务",
                    )
                }
                logger.event(LOG_TASK_ID, "SCHEDULER", "PLATFORM_CIRCUIT_OPENED", JSONObject().apply {
                    put("platform", platform.wireValue)
                    put("code", code)
                    put("http_statuses", httpStatuses.joinToString(","))
                    put("cooldown_ms", until - now)
                    put("repeated", repeated)
                })
            }
        }
    }

    fun reportPlatformSuccess(platform: SourcePlatform) {
        scope.launch {
            riskLoaded.await()
            riskMutex.withLock {
                if (!riskUntil.containsKey(platform)) return@withLock
                val updated = riskUntil.toMutableMap().apply { remove(platform) }
                riskUntil = updated
                settingsRepository.setPlatformRiskUntil(updated)
                _state.update { it.copy(platformRiskUntil = updated, reason = "${platform.displayName}请求已恢复") }
            }
            taskGate.dispatchEligible()
            logger.event(LOG_TASK_ID, "SCHEDULER", "PLATFORM_CIRCUIT_CLOSED", JSONObject().apply {
                put("platform", platform.wireValue)
            })
        }
    }

    private suspend fun sampleAndAdjust() {
        val elapsedNow = SystemClock.elapsedRealtime()
        val epochNow = System.currentTimeMillis()
        expireCircuits(epochNow)

        val freshSamples = transferSamples.entries.filter { elapsedNow - it.value.recordedAtMs <= SAMPLE_STALE_MS }
        transferSamples.entries.removeIf { elapsedNow - it.value.recordedAtMs > SAMPLE_STALE_MS }
        val instantaneousSpeed = freshSamples.sumOf { it.value.bytesPerSecond }
        val taskSpeeds = freshSamples.groupBy { it.value.taskId }
            .mapValues { (_, samples) -> samples.sumOf { it.value.bytesPerSecond } }
        ewmaSpeed = if (ewmaSpeed == 0.0) instantaneousSpeed.toDouble() else {
            EWMA_ALPHA * instantaneousSpeed + (1.0 - EWMA_ALPHA) * ewmaSpeed
        }

        val cpuNow = Process.getElapsedCpuTime()
        val wallDelta = (elapsedNow - previousWallMs).coerceAtLeast(1L)
        val cpuDelta = (cpuNow - previousCpuMs).coerceAtLeast(0L)
        previousCpuMs = cpuNow
        previousWallMs = elapsedNow
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuPercent = (cpuDelta.toDouble() / (wallDelta * cores) * 100.0).toFloat().coerceIn(0f, 100f)
        val frames = frameCount.getAndSet(0L)
        val slowFrames = slowFrameCount.getAndSet(0L)
        val slowFramePercent = if (frames > 0L) slowFrames * 100f / frames else -1f
        val thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        decisionCpuSum += cpuPercent
        decisionCpuSamples += 1
        decisionFrameCount += frames
        decisionSlowFrameCount += slowFrames
        decisionThermalMax = maxOf(decisionThermalMax, thermalStatus)
        val networkKey = currentNetworkKey()
        val networkChanged = previousNetworkKey != null && networkKey != previousNetworkKey
        previousNetworkKey = networkKey

        val (activeCount, waitingCount) = taskGate.counts()
        val waitingEligible = taskGate.eligibleWaitingCount()
        if (activeCount == 0 && waitingCount == 0) {
            policyState = AdaptivePolicyState()
            targetConcurrency = MIN_CONCURRENCY
            taskGate.setTarget(MIN_CONCURRENCY)
            ewmaSpeed = 0.0
            resetDecisionMetrics()
        } else if (elapsedNow - lastDecisionAtMs >= DECISION_INTERVAL_MS) {
            lastDecisionAtMs = elapsedNow
            val previousTarget = policyState.targetConcurrency
            val decisionCpuPercent = if (decisionCpuSamples > 0) {
                decisionCpuSum / decisionCpuSamples
            } else cpuPercent
            val decisionSlowFramePercent = if (decisionFrameCount > 0L) {
                decisionSlowFrameCount * 100f / decisionFrameCount
            } else -1f
            val decisionThermalStatus = decisionThermalMax
            resetDecisionMetrics()
            policyState = updateAdaptivePolicy(
                policyState,
                AdaptivePolicyInput(
                    nowMs = elapsedNow,
                    aggregateBytesPerSecond = ewmaSpeed.toLong(),
                    waitingEligibleTasks = waitingEligible,
                    cpuPercent = decisionCpuPercent,
                    slowFramePercent = decisionSlowFramePercent,
                    thermalStatus = decisionThermalStatus,
                    networkChanged = networkChanged,
                ),
            )
            targetConcurrency = policyState.targetConcurrency
            taskGate.setTarget(targetConcurrency)
            if (previousTarget != targetConcurrency || networkChanged) {
                logger.event(LOG_TASK_ID, "SCHEDULER", "CONCURRENCY_TARGET_CHANGED", JSONObject().apply {
                    put("from", previousTarget)
                    put("to", targetConcurrency)
                    put("speed_bytes_per_second", ewmaSpeed.toLong())
                    put("cpu_percent", decisionCpuPercent.toDouble())
                    put("slow_frame_percent", decisionSlowFramePercent.toDouble())
                    put("thermal_status", decisionThermalStatus)
                    put("network_changed", networkChanged)
                    put("reason", policyState.reason)
                })
            }
        }
        taskGate.dispatchEligible()
        val activeCircuit = riskUntil.entries.firstOrNull { it.value > epochNow }
        _state.update {
            it.copy(
                targetConcurrency = targetConcurrency,
                aggregateBytesPerSecond = ewmaSpeed.toLong(),
                taskBytesPerSecond = taskSpeeds,
                peakBytesPerSecond = policyState.peakBytesPerSecond,
                cpuPercent = cpuPercent,
                slowFramePercent = slowFramePercent,
                thermalStatus = thermalStatus,
                reason = activeCircuit?.let { (platform, _) ->
                    "${platform.displayName}触发风控，已暂停该平台新任务"
                } ?: policyState.reason,
                platformRiskUntil = riskUntil,
            )
        }
    }

    private suspend fun expireCircuits(nowEpochMs: Long) {
        val expired = riskMutex.withLock {
            val expiredPlatforms = riskUntil.filterValues { it <= nowEpochMs }.keys
            if (expiredPlatforms.isEmpty()) return@withLock emptySet()
            val updated = riskUntil.toMutableMap().apply { expiredPlatforms.forEach(::remove) }
            riskUntil = updated
            settingsRepository.setPlatformRiskUntil(updated)
            _state.update { it.copy(platformRiskUntil = updated, reason = "平台风控等待结束，正在谨慎恢复") }
            expiredPlatforms
        }
        expired.forEach { platform ->
            logger.event(LOG_TASK_ID, "SCHEDULER", "PLATFORM_CIRCUIT_HALF_OPEN", JSONObject().apply {
                put("platform", platform.wireValue)
            })
        }
    }

    private fun currentNetworkKey(): String {
        val network = connectivityManager?.activeNetwork ?: return "none"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return network.toString()
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
        ).filter(capabilities::hasTransport).joinToString(",")
        return "$network:$transports"
    }

    private fun resetDecisionMetrics() {
        decisionCpuSum = 0f
        decisionCpuSamples = 0
        decisionFrameCount = 0L
        decisionSlowFrameCount = 0L
        decisionThermalMax = PowerManager.THERMAL_STATUS_NONE
    }

    companion object {
        private const val LOG_TASK_ID = "adaptive-concurrency"
        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val DECISION_INTERVAL_MS = 10_000L
        private const val SAMPLE_STALE_MS = 3_500L
        private const val SLOW_FRAME_NANOS = 32_000_000L
        private const val EWMA_ALPHA = 0.3
        private const val INITIAL_RISK_COOLDOWN_MS = 2 * 60_000L
        private const val REPEATED_RISK_COOLDOWN_MS = 5 * 60_000L
        private const val REPEATED_RISK_WINDOW_MS = 10 * 60_000L
        val RISK_CODES = setOf("AUTH_OR_RISK", "RATE_LIMITED", "BILIBILI_RISK")
        val RISK_HTTP_STATUSES = setOf(403, 412, 429, 461)
    }
}
