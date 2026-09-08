package com.local.multiplatformdownloader.feature.creator

import com.local.multiplatformdownloader.app.MainActivity
import com.local.multiplatformdownloader.feature.download.DownloadWorker
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor

import com.local.multiplatformdownloader.R

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class CreatorBatchWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: CreatorBatchCoordinator,
    private val logger: DiagnosticLogger,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID).orEmpty()
        if (batchId.isBlank()) return Result.failure()
        return try {
            setForeground(createForeground(batchId))
            val platform = withContext(Dispatchers.IO) { coordinator.platformForBatch(batchId) }
                ?: return Result.failure()
            val cookie = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(platform.homeUrl).orEmpty()
            }
            val result = withContext(Dispatchers.IO) { coordinator.execute(batchId, cookie) }
            logger.event(batchId, "BATCH", "BATCH_WORKER_COMPLETE", JSONObject().apply {
                put("started", result.started)
                put("failed", result.failed)
                put("paused", result.paused)
                put("foreground_required", result.foregroundRequired)
            })
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.event(batchId, "BATCH", "BATCH_WORKER_FAILED", JSONObject().apply {
                put("type", error.javaClass.name)
                put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
            })
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun createForeground(batchId: String): ForegroundInfo {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, DownloadWorker.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("正在准备作者批量下载")
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            batchId.hashCode().and(0x7fffffff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
    }
}
