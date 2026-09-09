package com.local.multiplatformdownloader.feature.tasks

import android.webkit.CookieManager
import androidx.work.ExistingWorkPolicy
import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.feature.download.DownloadScheduler
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveCoordinator
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal class TaskCommandCoordinator @Inject constructor(
    private val store: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val scheduler: DownloadScheduler,
    private val redownloadCoordinator: TaskRedownloadCoordinator,
    private val questionArchiveCoordinator: ZhihuQuestionArchiveCoordinator,
) {
    suspend fun cancel(task: TaskRecord): String? {
        runCatching { logger.event(task.id, "DOWNLOAD", "CANCEL_REQUESTED") }
        return try {
            if (task.questionArchiveId.isNotBlank() && !task.questionChild) {
                questionArchiveCoordinator.cancel(task.id)
            } else {
                scheduler.cancel(task.id)
                store.update(task.id, TaskStatus.CANCELLED, "已取消", task.progress)
            }
            runCatching { logger.event(task.id, "DOWNLOAD", "CANCEL_ACCEPTED") }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val safeMessage = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            runCatching {
                logger.event(task.id, "DOWNLOAD", "CANCEL_FAILED", JSONObject().apply {
                    put("type", error.javaClass.name)
                    put("message", safeMessage)
                })
            }
            "取消失败：$safeMessage"
        }
    }

    suspend fun pause(task: TaskRecord): String? {
        if (task.status !in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) return null
        return try {
            runCatching { logger.event(task.id, "DOWNLOAD", "PAUSE_REQUESTED") }
            store.update(task.id, TaskStatus.PAUSED, "已暂停", task.progress)
            scheduler.cancel(task.id)
            runCatching { logger.event(task.id, "DOWNLOAD", "PAUSE_ACCEPTED") }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            "暂停失败：$detail"
        }
    }

    suspend fun resume(task: TaskRecord): String? {
        if (task.status != TaskStatus.PAUSED) return null
        return try {
            store.update(task.id, TaskStatus.QUEUED, "等待下载", task.progress)
            scheduler.enqueue(task.id, ExistingWorkPolicy.REPLACE)
            runCatching { logger.event(task.id, "DOWNLOAD", "RESUME_ACCEPTED") }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            store.update(task.id, TaskStatus.PAUSED, "继续失败", task.progress, detail)
            "继续下载失败：$detail"
        }
    }

    suspend fun retry(task: TaskRecord, customTreeUri: String?): String {
        if (task.questionArchiveId.isNotBlank() && !task.questionChild) {
            return questionArchiveCoordinator.resume(task.id)
        }
        val cookieHeader = CookieManager.getInstance().getCookie(task.platform.homeUrl).orEmpty()
        return redownloadCoordinator.retry(task, cookieHeader, customTreeUri).message
    }

    suspend fun continueQuestionArchive(task: TaskRecord): String? {
        if (task.questionArchiveId.isBlank() || task.questionChild) return null
        return questionArchiveCoordinator.continueNextPage(task.id)
    }

    suspend fun retryAll(tasks: List<TaskRecord>, customTreeUri: String?): String? {
        val uniqueTasks = tasks.distinctBy(TaskRecord::id)
        if (uniqueTasks.isEmpty()) return null
        var started = 0
        var failed = 0
        var skipped = 0
        uniqueTasks.forEach { task ->
            if (!isTaskRedownloadEligible(task)) {
                skipped += 1
                return@forEach
            }
            if (task.questionArchiveId.isNotBlank() && !task.questionChild) {
                val resumed = runCatching {
                    questionArchiveCoordinator.resume(task.id)
                }.isSuccess
                if (resumed) started += 1 else failed += 1
                return@forEach
            }
            val cookieHeader = CookieManager.getInstance().getCookie(task.platform.homeUrl).orEmpty()
            val result = runCatching {
                redownloadCoordinator.retry(task, cookieHeader, customTreeUri)
            }.getOrElse { error ->
                runCatching {
                    logger.event(task.id, "REDOWNLOAD", "BATCH_REDOWNLOAD_FAILED", JSONObject().apply {
                        put("type", error.javaClass.name)
                        put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
                    })
                }
                TaskRedownloadResult(false, "重新下载失败")
            }
            if (result.success) started += 1 else failed += 1
        }
        return batchRedownloadSummary(started, failed, skipped)
    }
}
