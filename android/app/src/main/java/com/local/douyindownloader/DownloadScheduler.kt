package com.local.douyindownloader

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun enqueue(taskId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString(DownloadWorker.KEY_TASK_ID, taskId).build())
            .addTag(taskId)
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(taskId), policy, request)
    }

    fun cancel(taskId: String) {
        workManager.cancelAllWorkByTag(taskId)
    }

    companion object {
        fun uniqueWorkName(taskId: String): String = "douyin-$taskId"
    }
}
