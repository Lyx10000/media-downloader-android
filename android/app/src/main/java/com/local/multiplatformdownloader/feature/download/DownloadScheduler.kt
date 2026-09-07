package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Singleton
class DownloadScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val logger: DiagnosticLogger,
) {
    suspend fun enqueue(taskId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString(DownloadWorker.KEY_TASK_ID, taskId).build())
            .addTag(taskId)
            .build()
        safeEvent(taskId, "SCHEDULE_REQUESTED", JSONObject().apply {
            put("policy", policy.name)
            put("work_id", request.id.toString())
        })
        try {
            workManager.enqueueUniqueWork(uniqueWorkName(taskId), policy, request).await()
            safeEvent(taskId, "SCHEDULE_ACCEPTED", JSONObject().apply {
                put("policy", policy.name)
                put("work_id", request.id.toString())
            })
        } catch (cancelled: CancellationException) {
            safeEvent(taskId, "SCHEDULE_OBSERVER_CANCELLED", JSONObject().apply {
                put("policy", policy.name)
                put("work_id", request.id.toString())
            })
            throw cancelled
        } catch (error: Throwable) {
            safeEvent(taskId, "SCHEDULE_FAILED", JSONObject().apply {
                put("policy", policy.name)
                put("work_id", request.id.toString())
                put("type", error.javaClass.name)
                put("message", Redactor.sanitize(error.message.orEmpty()))
            })
            throw error
        }
    }

    suspend fun cancel(taskId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(taskId)).await()
    }

    private fun safeEvent(taskId: String, name: String, details: JSONObject) {
        runCatching { logger.event(taskId, "SCHEDULE", name, details) }
    }

    companion object {
        fun uniqueWorkName(taskId: String): String = "douyin-$taskId"
    }
}
