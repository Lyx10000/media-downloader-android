package com.local.multiplatformdownloader.core.download

import kotlin.math.max

internal data class AdaptivePolicyState(
    val targetConcurrency: Int = 1,
    val stableWindows: Int = 0,
    val trialStartedAtMs: Long = 0L,
    val trialBaselineBytesPerSecond: Long = 0L,
    val cooldownUntilMs: Long = 0L,
    val peakBytesPerSecond: Long = 0L,
    val reason: String = "从单任务开始评估",
)

internal data class AdaptivePolicyInput(
    val nowMs: Long,
    val aggregateBytesPerSecond: Long,
    val waitingEligibleTasks: Int,
    val cpuPercent: Float,
    val slowFramePercent: Float,
    val thermalStatus: Int,
    val networkChanged: Boolean = false,
)

internal fun updateAdaptivePolicy(
    state: AdaptivePolicyState,
    input: AdaptivePolicyInput,
): AdaptivePolicyState {
    val peak = max(state.peakBytesPerSecond, input.aggregateBytesPerSecond)
    if (input.networkChanged) {
        return AdaptivePolicyState(
            peakBytesPerSecond = input.aggregateBytesPerSecond,
            reason = "网络已切换，从单任务重新评估",
        )
    }
    val overloaded = input.cpuPercent >= CPU_EMERGENCY_PERCENT ||
        input.slowFramePercent >= SLOW_FRAME_EMERGENCY_PERCENT ||
        input.thermalStatus >= THERMAL_MODERATE
    if (overloaded) {
        return state.copy(
            targetConcurrency = 1,
            stableWindows = 0,
            trialStartedAtMs = 0L,
            trialBaselineBytesPerSecond = 0L,
            cooldownUntilMs = input.nowMs + OVERLOAD_COOLDOWN_MS,
            peakBytesPerSecond = peak,
            reason = when {
                input.thermalStatus >= THERMAL_MODERATE -> "设备温度升高，已降低并发"
                input.slowFramePercent >= SLOW_FRAME_EMERGENCY_PERCENT -> "界面出现明显卡顿，已降低并发"
                else -> "应用 CPU 负载较高，已降低并发"
            },
        )
    }
    if (state.trialStartedAtMs > 0L && input.nowMs - state.trialStartedAtMs >= TRIAL_DURATION_MS) {
        val useful = input.aggregateBytesPerSecond >=
            (state.trialBaselineBytesPerSecond * MIN_TRIAL_GAIN).toLong()
        return if (useful) {
            state.copy(
                stableWindows = 0,
                trialStartedAtMs = 0L,
                trialBaselineBytesPerSecond = 0L,
                peakBytesPerSecond = peak,
                reason = "新增并发提高了总下载速度",
            )
        } else {
            state.copy(
                targetConcurrency = (state.targetConcurrency - 1).coerceAtLeast(MIN_CONCURRENCY),
                stableWindows = 0,
                trialStartedAtMs = 0L,
                trialBaselineBytesPerSecond = 0L,
                cooldownUntilMs = input.nowMs + FAILED_TRIAL_COOLDOWN_MS,
                peakBytesPerSecond = peak,
                reason = "新增并发没有带来有效提速",
            )
        }
    }
    val canProbe = input.waitingEligibleTasks > 0 &&
        state.targetConcurrency < MAX_CONCURRENCY &&
        input.aggregateBytesPerSecond > 0L &&
        input.cpuPercent < CPU_PROBE_PERCENT &&
        (input.slowFramePercent < 0f || input.slowFramePercent < SLOW_FRAME_PROBE_PERCENT) &&
        input.nowMs >= state.cooldownUntilMs &&
        state.trialStartedAtMs == 0L
    if (!canProbe) {
        return state.copy(stableWindows = 0, peakBytesPerSecond = peak)
    }
    val stable = state.stableWindows + 1
    if (stable < REQUIRED_STABLE_WINDOWS) {
        return state.copy(stableWindows = stable, peakBytesPerSecond = peak)
    }
    return state.copy(
        targetConcurrency = (state.targetConcurrency + 1).coerceAtMost(MAX_CONCURRENCY),
        stableWindows = 0,
        trialStartedAtMs = input.nowMs,
        trialBaselineBytesPerSecond = input.aggregateBytesPerSecond,
        peakBytesPerSecond = peak,
        reason = "正在试探更高并发是否能够提速",
    )
}

internal const val MIN_CONCURRENCY = 1
internal const val MAX_CONCURRENCY = 3
private const val REQUIRED_STABLE_WINDOWS = 2
private const val CPU_PROBE_PERCENT = 35f
private const val CPU_EMERGENCY_PERCENT = 60f
private const val SLOW_FRAME_PROBE_PERCENT = 8f
private const val SLOW_FRAME_EMERGENCY_PERCENT = 20f
private const val THERMAL_MODERATE = 2
private const val MIN_TRIAL_GAIN = 1.15
private const val TRIAL_DURATION_MS = 12_000L
private const val FAILED_TRIAL_COOLDOWN_MS = 30_000L
private const val OVERLOAD_COOLDOWN_MS = 30_000L
