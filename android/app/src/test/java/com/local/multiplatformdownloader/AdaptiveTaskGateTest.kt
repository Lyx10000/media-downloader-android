package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.AdaptiveTaskGate
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveTaskGateTest {
    @Test
    fun `raising target admits queued task`() = runTest {
        val gate = AdaptiveTaskGate<String>(initialTarget = 1)
        val first = gate.acquire("first", "a")
        val second = async { gate.acquire("second", "a") }
        runCurrent()

        assertEquals(1 to 1, gate.counts())
        gate.setTarget(2)
        runCurrent()
        assertTrue(second.isCompleted)
        assertEquals(2 to 0, gate.counts())

        gate.release(first)
        gate.release(second.await())
    }

    @Test
    fun `shrinking drains active tasks instead of cancelling them`() = runTest {
        val gate = AdaptiveTaskGate<String>(initialTarget = 3)
        val first = gate.acquire("first", "a")
        val second = gate.acquire("second", "a")
        val third = gate.acquire("third", "a")
        gate.setTarget(1)
        val fourth = async { gate.acquire("fourth", "a") }
        runCurrent()

        gate.release(first)
        gate.release(second)
        assertFalse(fourth.isCompleted)
        assertEquals(1 to 1, gate.counts())

        gate.release(third)
        runCurrent()
        assertTrue(fourth.isCompleted)
        gate.release(fourth.await())
    }

    @Test
    fun `cancelling waiter does not leak a slot`() = runTest {
        val gate = AdaptiveTaskGate<String>(initialTarget = 1)
        val first = gate.acquire("first", "a")
        val waiting = async { gate.acquire("cancelled", "a") }
        runCurrent()
        waiting.cancelAndJoin()

        assertEquals(1 to 0, gate.counts())
        gate.release(first)
        assertEquals(0 to 0, gate.counts())
    }

    @Test
    fun `blocked platform does not hold eligible platform behind it`() = runTest {
        val blocked = mutableSetOf("blocked")
        val gate = AdaptiveTaskGate(initialTarget = 1, isBlocked = blocked::contains)
        val blockedTask = async { gate.acquire("blocked-task", "blocked") }
        runCurrent()
        val eligible = gate.acquire("eligible-task", "open")

        assertFalse(blockedTask.isCompleted)
        assertEquals(1 to 1, gate.counts())
        gate.release(eligible)
        blocked.remove("blocked")
        gate.dispatchEligible()
        runCurrent()
        assertTrue(blockedTask.isCompleted)
        gate.release(blockedTask.await())
    }
}
