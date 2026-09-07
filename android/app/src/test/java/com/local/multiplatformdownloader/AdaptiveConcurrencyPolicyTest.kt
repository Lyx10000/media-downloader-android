package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.AdaptivePolicyInput
import com.local.multiplatformdownloader.core.download.AdaptivePolicyState
import com.local.multiplatformdownloader.core.download.updateAdaptivePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveConcurrencyPolicyTest {
    @Test
    fun `two stable windows start one-slot trial`() {
        val first = updateAdaptivePolicy(AdaptivePolicyState(), healthy(now = 10_000L))
        val second = updateAdaptivePolicy(first, healthy(now = 20_000L))

        assertEquals(2, second.targetConcurrency)
        assertEquals(20_000L, second.trialStartedAtMs)
        assertEquals(1_000_000L, second.trialBaselineBytesPerSecond)
    }

    @Test
    fun `trial is retained only when aggregate throughput improves enough`() {
        val trial = AdaptivePolicyState(
            targetConcurrency = 2,
            trialStartedAtMs = 20_000L,
            trialBaselineBytesPerSecond = 1_000_000L,
        )
        val useful = updateAdaptivePolicy(
            trial,
            healthy(now = 32_000L, speed = 1_200_000L),
        )
        val ineffective = updateAdaptivePolicy(
            trial,
            healthy(now = 32_000L, speed = 1_100_000L),
        )

        assertEquals(2, useful.targetConcurrency)
        assertEquals(0L, useful.trialStartedAtMs)
        assertEquals(1, ineffective.targetConcurrency)
        assertTrue(ineffective.cooldownUntilMs > 32_000L)
    }

    @Test
    fun `overload drains target to one without corrupting peak`() {
        val result = updateAdaptivePolicy(
            AdaptivePolicyState(targetConcurrency = 3, peakBytesPerSecond = 2_000_000L),
            healthy(now = 40_000L, speed = 1_500_000L).copy(cpuPercent = 70f),
        )

        assertEquals(1, result.targetConcurrency)
        assertEquals(2_000_000L, result.peakBytesPerSecond)
        assertTrue(result.reason.contains("CPU"))
    }

    @Test
    fun `network switch resets learned concurrency`() {
        val result = updateAdaptivePolicy(
            AdaptivePolicyState(targetConcurrency = 3, peakBytesPerSecond = 3_000_000L),
            healthy(now = 50_000L, speed = 500_000L).copy(networkChanged = true),
        )

        assertEquals(1, result.targetConcurrency)
        assertEquals(500_000L, result.peakBytesPerSecond)
        assertTrue(result.reason.contains("网络"))
    }

    private fun healthy(now: Long, speed: Long = 1_000_000L) = AdaptivePolicyInput(
        nowMs = now,
        aggregateBytesPerSecond = speed,
        waitingEligibleTasks = 2,
        cpuPercent = 10f,
        slowFramePercent = 1f,
        thermalStatus = 0,
    )
}
