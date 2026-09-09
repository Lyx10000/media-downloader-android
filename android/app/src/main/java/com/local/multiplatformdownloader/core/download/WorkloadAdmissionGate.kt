package com.local.multiplatformdownloader.core.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore

/**
 * Bounds work before heavyweight task state, parsers and foreground services are created.
 * Transfer concurrency is still adjusted independently by [AdaptiveDownloadController].
 */
@Singleton
internal class WorkloadAdmissionGate @Inject constructor() {
    private val batchPreparationMutex = Mutex()
    private val downloadWorkerPermits = Semaphore(MAX_ADMITTED_DOWNLOAD_WORKERS)

    suspend fun <T> withBatchPreparationPermit(block: suspend () -> T): T {
        batchPreparationMutex.lock()
        return try {
            block()
        } finally {
            batchPreparationMutex.unlock()
        }
    }

    suspend fun <T> withDownloadWorkerPermit(block: suspend () -> T): T {
        downloadWorkerPermits.acquire()
        return try {
            block()
        } finally {
            downloadWorkerPermits.release()
        }
    }

    companion object {
        internal const val MAX_ADMITTED_DOWNLOAD_WORKERS = MAX_CONCURRENCY
    }
}
