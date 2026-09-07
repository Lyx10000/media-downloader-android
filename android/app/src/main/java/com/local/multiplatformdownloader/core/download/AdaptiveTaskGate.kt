package com.local.multiplatformdownloader.core.download

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AdaptiveTaskLease<P>(
    val token: String,
    val taskId: String,
    val platform: P,
)

/** A shrinkable FIFO gate: lowering the target never cancels an active lease. */
internal class AdaptiveTaskGate<P>(
    initialTarget: Int = 1,
    private val isBlocked: (P) -> Boolean = { false },
    private val onCountsChanged: (active: Int, waiting: Int) -> Unit = { _, _ -> },
) {
    private data class Waiter<P>(
        val lease: AdaptiveTaskLease<P>,
        val ready: CompletableDeferred<Unit>,
    )

    private val mutex = Mutex()
    private val active = linkedMapOf<String, AdaptiveTaskLease<P>>()
    private val activeTokenByTask = ConcurrentHashMap<String, String>()
    private val waiting = mutableListOf<Waiter<P>>()
    private var target = initialTarget.coerceAtLeast(1)

    suspend fun acquire(taskId: String, platform: P): AdaptiveTaskLease<P> {
        val lease = AdaptiveTaskLease(UUID.randomUUID().toString(), taskId, platform)
        val ready = CompletableDeferred<Unit>()
        var admitted = false
        mutex.withLock {
            if (active.size < target && !isBlocked(platform)) {
                admitLocked(lease)
                admitted = true
            } else {
                waiting += Waiter(lease, ready)
            }
            publishCountsLocked()
        }
        if (!admitted) {
            try {
                ready.await()
            } catch (cancelled: CancellationException) {
                val resumed = mutex.withLock {
                    waiting.removeAll { it.lease.token == lease.token }
                    if (active.remove(lease.token) != null) {
                        activeTokenByTask.remove(lease.taskId, lease.token)
                    }
                    val selected = dispatchLocked()
                    publishCountsLocked()
                    selected
                }
                resumed.forEach { it.complete(Unit) }
                throw cancelled
            }
        }
        return lease
    }

    suspend fun release(lease: AdaptiveTaskLease<P>) {
        val resumed = mutex.withLock {
            active.remove(lease.token)
            activeTokenByTask.remove(lease.taskId, lease.token)
            dispatchLocked().also { publishCountsLocked() }
        }
        resumed.forEach { it.complete(Unit) }
    }

    suspend fun setTarget(value: Int) {
        val resumed = mutex.withLock {
            target = value.coerceAtLeast(1)
            dispatchLocked().also { publishCountsLocked() }
        }
        resumed.forEach { it.complete(Unit) }
    }

    suspend fun dispatchEligible() {
        val resumed = mutex.withLock { dispatchLocked().also { publishCountsLocked() } }
        resumed.forEach { it.complete(Unit) }
    }

    suspend fun counts(): Pair<Int, Int> = mutex.withLock { active.size to waiting.size }

    suspend fun eligibleWaitingCount(): Int = mutex.withLock {
        waiting.count { !isBlocked(it.lease.platform) }
    }

    fun tokenForTask(taskId: String): String? = activeTokenByTask[taskId]

    private fun dispatchLocked(): List<CompletableDeferred<Unit>> {
        val resumed = mutableListOf<CompletableDeferred<Unit>>()
        while (active.size < target) {
            val index = waiting.indexOfFirst { !isBlocked(it.lease.platform) }
            if (index < 0) break
            val waiter = waiting.removeAt(index)
            admitLocked(waiter.lease)
            resumed += waiter.ready
        }
        return resumed
    }

    private fun admitLocked(lease: AdaptiveTaskLease<P>) {
        active[lease.token] = lease
        activeTokenByTask[lease.taskId] = lease.token
    }

    private fun publishCountsLocked() = onCountsChanged(active.size, waiting.size)
}
