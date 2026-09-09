package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.download.WorkloadAdmissionGate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkloadAdmissionGateTest {
    @Test
    fun `download admission never exceeds the safe worker window`() = runTest {
        val gate = WorkloadAdmissionGate()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()
        val jobs = (1..6).map {
            async {
                gate.withDownloadWorkerPermit {
                    val current = active.incrementAndGet()
                    peak.updateAndGet { previous -> maxOf(previous, current) }
                    release.await()
                    active.decrementAndGet()
                }
            }
        }

        runCurrent()
        assertEquals(WorkloadAdmissionGate.MAX_ADMITTED_DOWNLOAD_WORKERS, active.get())
        assertEquals(WorkloadAdmissionGate.MAX_ADMITTED_DOWNLOAD_WORKERS, peak.get())

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, active.get())
        assertTrue(jobs.all { it.isCompleted })
    }

    @Test
    fun `only one creator batch prepares at a time`() = runTest {
        val gate = WorkloadAdmissionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            gate.withBatchPreparationPermit {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            gate.withBatchPreparationPermit { secondEntered.complete(Unit) }
        }

        runCurrent()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertTrue(secondEntered.isCompleted)
    }
}
