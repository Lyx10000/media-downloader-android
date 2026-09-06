package com.local.douyindownloader

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

internal class ZhihuQuestionArchiveWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: ZhihuQuestionArchiveCoordinator,
    private val logger: DiagnosticLogger,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty()
        if (taskId.isBlank()) return Result.failure()
        return try {
            setForeground(createForeground(taskId))
            val cookie = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(SourcePlatform.ZHIHU.homeUrl).orEmpty()
            }
            when (withContext(Dispatchers.IO) { coordinator.execute(taskId, cookie) }) {
                ZhihuQuestionRunResult.COMPLETE,
                ZhihuQuestionRunResult.PAUSED,
                -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.event(taskId, "QUESTION", "QUESTION_ARCHIVE_FAILED", JSONObject().apply {
                put("type", error.javaClass.name)
                put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
            })
            if (runAttemptCount < 2) {
                Result.retry()
            } else {
                withContext(Dispatchers.IO) { coordinator.fail(taskId, error) }
                Result.failure()
            }
        }
    }

    private fun createForeground(taskId: String): ForegroundInfo {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, DownloadWorker.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("聚合下载器")
            .setContentText("正在归档知乎问题")
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            taskId.hashCode().and(0x7fffffff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
