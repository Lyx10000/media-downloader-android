package com.local.multiplatformdownloader.feature.zhihuarchive


import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.feature.download.DownloadScheduler
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.StorageMode
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.storage.PublicStorage
import com.local.multiplatformdownloader.core.settings.BatchVideoQuality
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryRepository
import com.local.multiplatformdownloader.feature.creator.chooseBatchVariant
import com.local.multiplatformdownloader.feature.creator.sanitizeFolderSegment
import com.local.multiplatformdownloader.core.settings.SettingsRepository
import com.local.multiplatformdownloader.feature.tasks.TaskDeletionCoordinator
import com.local.multiplatformdownloader.platform.common.ParserGateway
import com.local.multiplatformdownloader.platform.common.PlatformParseException
import com.local.multiplatformdownloader.platform.zhihu.ZhihuQuestionSource

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

internal enum class ZhihuQuestionRunResult { COMPLETE, PAUSED }

@Singleton
internal class ZhihuQuestionArchiveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val questions: ZhihuQuestionRepository,
    private val tasks: DownloadTaskRepository,
    private val source: ZhihuQuestionSource,
    private val parser: ParserGateway,
    private val creatorRepository: CreatorLibraryRepository,
    private val scheduler: DownloadScheduler,
    private val deletionCoordinator: TaskDeletionCoordinator,
    private val settingsRepository: SettingsRepository,
    private val logger: DiagnosticLogger,
    private val workManager: WorkManager,
) {
    suspend fun start(
        taskId: String,
        result: ParseResult,
        scope: ZhihuQuestionDownloadScope,
        includeComments: Boolean,
    ): String {
        val info = result.question ?: error("知乎问题信息不存在")
        val createdAt = System.currentTimeMillis()
        val settings = settingsRepository.current()
        val taskFolder = zhihuQuestionFolder(info)
        val spec = TaskSpec(
            taskId = taskId,
            createdAt = createdAt,
            result = result,
            variantIndex = 0,
            mode = DownloadMode.MERGE_KEEP,
            sourceText = info.canonicalUrl,
            storageMode = if (settings.customTreeUri.isNullOrBlank()) StorageMode.DEFAULT else StorageMode.SAF,
            storageRoot = settings.customTreeUri.orEmpty(),
            taskFolder = taskFolder,
            questionArchiveId = info.questionId,
        )
        tasks.insert(spec)
        questions.upsert(
            ZhihuQuestionArchive(
                questionId = info.questionId,
                parentTaskId = taskId,
                title = info.title,
                canonicalUrl = info.canonicalUrl,
                answerCount = info.answerCount,
                nextOffset = 0,
                hasMore = true,
                scope = scope,
                includeComments = includeComments,
                status = ZhihuQuestionStatus.QUEUED,
                taskFolder = taskFolder,
                createdAt = createdAt,
                refreshedAt = 0L,
            ),
        )
        enqueue(taskId, ExistingWorkPolicy.KEEP)
        logger.event(taskId, "QUESTION", "QUESTION_ARCHIVE_QUEUED", JSONObject().apply {
            put("question_id", info.questionId)
            put("scope", scope.wireValue)
            put("include_comments", includeComments)
            put("answer_count", info.answerCount)
        })
        return taskId
    }

    suspend fun execute(parentTaskId: String, cookieHeader: String): ZhihuQuestionRunResult {
        val archive = questions.getByTask(parentTaskId) ?: error("知乎问题归档记录不存在")
        val parentSpec = tasks.getSpec(parentTaskId) ?: error("知乎问题主任务不存在")
        questions.updateStatus(archive.questionId, ZhihuQuestionStatus.RUNNING)
        tasks.update(parentTaskId, TaskStatus.RUNNING, "正在读取回答列表", 0)

        if (!preparePendingAnswers(archive, parentSpec, cookieHeader)) {
            pauseArchive(archive, parentTaskId, "知乎登录状态或风控拒绝了回答详情请求")
            return ZhihuQuestionRunResult.PAUSED
        }

        var current = questions.get(archive.questionId) ?: archive
        var pagesFetched = 0
        while (canContinueQuestionArchive(current, questions.listAnswers(current.questionId).size)) {
            if (current.scope == ZhihuQuestionDownloadScope.FIRST_PAGE && pagesFetched >= 1) break
            val page = try {
                source.fetchPage(current.questionId, current.nextOffset.coerceAtLeast(0), cookieHeader)
            } catch (error: PlatformParseException) {
                if (error.code in setOf("AUTH_OR_RISK", "LOGIN_REQUIRED")) {
                    pauseArchive(current, parentTaskId, error.message.orEmpty())
                    return ZhihuQuestionRunResult.PAUSED
                }
                throw error
            }
            questions.savePage(current.questionId, page)
            pagesFetched += 1
            logger.event(parentTaskId, "QUESTION", "ANSWER_PAGE_FETCHED", JSONObject().apply {
                put("question_id", current.questionId)
                put("offset", current.nextOffset)
                put("answers", page.answers.size)
                put("next_offset", page.nextOffset)
                put("has_more", page.hasMore)
            })
            if (!prepareAnswers(current, parentSpec, page.answers, cookieHeader)) {
                pauseArchive(current, parentTaskId, "知乎登录状态或风控拒绝了回答详情请求")
                return ZhihuQuestionRunResult.PAUSED
            }
            current = questions.get(current.questionId) ?: current.copy(
                nextOffset = page.nextOffset,
                hasMore = page.hasMore,
            )
            if (!page.hasMore) {
                current = current.copy(nextOffset = NO_MORE_ANSWERS_OFFSET, hasMore = false)
                questions.upsert(current)
            }
            val expected = current.answerCount.coerceAtLeast(1)
            val discovered = questions.listAnswers(current.questionId).size
            val progress = ((discovered * 35L) / expected).toInt().coerceIn(0, 35)
            tasks.update(parentTaskId, TaskStatus.RUNNING, "已读取 $discovered 条回答", progress)
        }

        waitForChildren(current, parentTaskId)
        synchronizeAnswerStatuses(current.questionId)
        val answers = questions.listAnswers(current.questionId)
        val failed = answers.count { answer ->
            answer.taskId.isBlank() || tasks.get(answer.taskId)?.status in setOf(
                TaskStatus.FAILED,
                TaskStatus.CANCELLED,
            )
        }
        val output = publishIndex(parentSpec, current, answers)
        val canContinue = canContinueQuestionArchive(current, answers.size)
        tasks.complete(
            parentTaskId,
            listOf(output),
            when {
                failed > 0 -> "已完成（$failed 条回答失败）"
                canContinue -> "本批已完成（已下载 ${answers.size} 条，可继续）"
                else -> "已完成"
            },
        )
        questions.updateStatus(
            current.questionId,
            if (failed > 0) ZhihuQuestionStatus.PARTIAL else ZhihuQuestionStatus.COMPLETE,
        )
        logger.event(parentTaskId, "QUESTION", "QUESTION_ARCHIVE_COMPLETE", JSONObject().apply {
            put("question_id", current.questionId)
            put("answers", answers.size)
            put("failed", failed)
            put("pages", pagesFetched)
            put("can_continue", canContinue)
        })
        return ZhihuQuestionRunResult.COMPLETE
    }

    suspend fun resume(parentTaskId: String): String {
        val archive = questions.getByTask(parentTaskId) ?: return "知乎问题归档记录不存在"
        questions.updateStatus(archive.questionId, ZhihuQuestionStatus.QUEUED)
        tasks.update(parentTaskId, TaskStatus.QUEUED, "等待继续归档", 0)
        enqueue(parentTaskId, ExistingWorkPolicy.REPLACE)
        return "已继续归档知乎问题"
    }

    suspend fun continueNextPage(parentTaskId: String): String {
        val archive = questions.getByTask(parentTaskId) ?: return "知乎问题归档记录不存在"
        val downloaded = questions.listAnswers(archive.questionId).size
        if (!canContinueQuestionArchive(archive, downloaded)) return "当前已没有更多可见回答"
        if (archive.status in setOf(ZhihuQuestionStatus.QUEUED, ZhihuQuestionStatus.RUNNING)) {
            return "当前批次仍在下载"
        }
        val continued = archive.copy(
            hasMore = true,
            scope = ZhihuQuestionDownloadScope.FIRST_PAGE,
            status = ZhihuQuestionStatus.QUEUED,
            error = "",
        )
        questions.upsert(continued)
        tasks.update(parentTaskId, TaskStatus.QUEUED, "等待下载下一批回答", 0)
        enqueue(parentTaskId, ExistingWorkPolicy.REPLACE)
        logger.event(parentTaskId, "QUESTION", "QUESTION_CONTINUE_QUEUED", JSONObject().apply {
            put("question_id", archive.questionId)
            put("next_offset", archive.nextOffset)
            put("downloaded", downloaded)
        })
        return "已加入下一批回答"
    }

    suspend fun retryAnswer(parentTaskId: String, answerId: String, cookieHeader: String): String {
        val archive = questions.getByTask(parentTaskId) ?: return "知乎问题归档记录不存在"
        val parentSpec = tasks.getSpec(parentTaskId) ?: return "知乎问题主任务不存在"
        val answer = questions.listAnswers(archive.questionId)
            .firstOrNull { it.answerId == answerId }
            ?: return "回答记录不存在"
        questions.updateAnswerStatus(
            archive.questionId,
            answer.answerId,
            ZhihuQuestionAnswerStatus.PREPARING,
        )
        val result = parser.parse(answer.canonicalUrl, cookieHeader)
        if (!result.ok) {
            val status = if (result.errorCode in setOf("AUTH_OR_RISK", "LOGIN_REQUIRED")) {
                ZhihuQuestionAnswerStatus.PAUSED
            } else {
                ZhihuQuestionAnswerStatus.FAILED
            }
            questions.updateAnswerStatus(archive.questionId, answer.answerId, status, result.message)
            return result.message.ifBlank { "回答重新解析失败" }
        }
        if (answer.taskId.isNotBlank()) {
            val removed = deletionCoordinator.deleteTask(answer.taskId, deleteFiles = true)
            if (!removed.success) {
                questions.updateAnswerStatus(
                    archive.questionId,
                    answer.answerId,
                    ZhihuQuestionAnswerStatus.FAILED,
                    removed.message,
                )
                return removed.message
            }
        }
        val taskId = UUID.randomUUID().toString()
        val childSpec = createChildSpec(archive, parentSpec, answer, result, taskId)
        tasks.insert(childSpec)
        questions.updateAnswer(
            archive.questionId,
            answer.answerId,
            taskId,
            ZhihuQuestionAnswerStatus.QUEUED,
        )
        scheduler.enqueue(taskId, ExistingWorkPolicy.KEEP)
        resume(parentTaskId)
        return "已重新准备该回答"
    }

    suspend fun cancel(parentTaskId: String) {
        val archive = questions.getByTask(parentTaskId) ?: return
        workManager.cancelUniqueWork(uniqueWorkName(parentTaskId)).await()
        questions.listAnswers(archive.questionId).mapNotNull { it.taskId.takeIf(String::isNotBlank) }
            .forEach { scheduler.cancel(it) }
        questions.updateStatus(archive.questionId, ZhihuQuestionStatus.CANCELLED)
        tasks.update(parentTaskId, TaskStatus.CANCELLED, "已取消", 0)
    }

    suspend fun deleteAnswer(parentTaskId: String, answerId: String): String {
        val archive = questions.getByTask(parentTaskId) ?: return "知乎问题归档记录不存在"
        if (archive.status in setOf(ZhihuQuestionStatus.QUEUED, ZhihuQuestionStatus.RUNNING)) {
            return "当前批次仍在下载，完成或取消后才能删除回答"
        }
        val answer = questions.listAnswers(archive.questionId)
            .firstOrNull { it.answerId == answerId }
            ?: return "回答已经删除"
        if (answer.taskId.isNotBlank()) {
            val result = deletionCoordinator.deleteTask(answer.taskId, deleteFiles = true)
            if (!result.success) return result.message
        }
        questions.deleteAnswer(archive.questionId, answer.answerId)
        val remaining = questions.listAnswers(archive.questionId)
        val parentSpec = tasks.getSpec(parentTaskId) ?: return "回答已删除，但问题索引无法更新"
        val output = publishIndex(parentSpec, archive, remaining)
        tasks.complete(
            parentTaskId,
            listOf(output),
            if (canContinueQuestionArchive(archive, remaining.size)) {
                "已删除回答（仍可继续下载）"
            } else {
                "已删除回答"
            },
        )
        logger.event(parentTaskId, "QUESTION", "ANSWER_DELETED", JSONObject().apply {
            put("question_id", archive.questionId)
            put("answer_id", answer.answerId)
            put("remaining", remaining.size)
        })
        return "回答及其下载内容已删除"
    }

    suspend fun fail(parentTaskId: String, error: Throwable) {
        val archive = questions.getByTask(parentTaskId) ?: return
        val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
        questions.updateStatus(archive.questionId, ZhihuQuestionStatus.FAILED, message)
        tasks.update(parentTaskId, TaskStatus.FAILED, "问题归档失败", 0, message)
    }

    private suspend fun preparePendingAnswers(
        archive: ZhihuQuestionArchive,
        parentSpec: TaskSpec,
        cookieHeader: String,
    ): Boolean = prepareAnswers(
        archive,
        parentSpec,
        questions.listAnswers(archive.questionId).filter {
            it.taskId.isBlank() && it.status in setOf(
                ZhihuQuestionAnswerStatus.DISCOVERED,
                ZhihuQuestionAnswerStatus.PAUSED,
                ZhihuQuestionAnswerStatus.FAILED,
            )
        },
        cookieHeader,
    )

    private suspend fun prepareAnswers(
        archive: ZhihuQuestionArchive,
        parentSpec: TaskSpec,
        answers: List<ZhihuQuestionAnswer>,
        cookieHeader: String,
    ): Boolean {
        for (answer in answers) {
            val existing = questions.listAnswers(archive.questionId)
                .firstOrNull { it.answerId == answer.answerId }
            if (!existing?.taskId.isNullOrBlank()) continue
            questions.updateAnswerStatus(
                archive.questionId,
                answer.answerId,
                ZhihuQuestionAnswerStatus.PREPARING,
            )
            val result = parser.parse(answer.canonicalUrl, cookieHeader)
            if (!result.ok) {
                val paused = result.errorCode in setOf("AUTH_OR_RISK", "LOGIN_REQUIRED")
                questions.updateAnswerStatus(
                    archive.questionId,
                    answer.answerId,
                    if (paused) ZhihuQuestionAnswerStatus.PAUSED else ZhihuQuestionAnswerStatus.FAILED,
                    result.message,
                )
                logger.event(parentSpec.taskId, "QUESTION", "ANSWER_PREPARE_FAILED", JSONObject().apply {
                    put("question_id", archive.questionId)
                    put("answer_id", answer.answerId)
                    put("code", result.errorCode)
                    put("message", result.message)
                })
                if (paused) return false
                continue
            }
            val taskId = UUID.randomUUID().toString()
            val childSpec = createChildSpec(archive, parentSpec, answer, result, taskId)
            try {
                tasks.insert(childSpec)
                questions.updateAnswer(
                    archive.questionId,
                    answer.answerId,
                    taskId,
                    ZhihuQuestionAnswerStatus.QUEUED,
                )
                scheduler.enqueue(taskId, ExistingWorkPolicy.KEEP)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                questions.updateAnswer(
                    archive.questionId,
                    answer.answerId,
                    taskId,
                    ZhihuQuestionAnswerStatus.FAILED,
                    error.message.orEmpty(),
                )
            }
        }
        return true
    }

    private suspend fun createChildSpec(
        archive: ZhihuQuestionArchive,
        parentSpec: TaskSpec,
        answer: ZhihuQuestionAnswer,
        result: ParseResult,
        taskId: String,
    ): TaskSpec = TaskSpec(
        taskId = taskId,
        createdAt = System.currentTimeMillis(),
        result = result,
        variantIndex = chooseBatchVariant(
            result.variants,
            BatchVideoQuality.HIGHEST,
            preferH264 = false,
        ),
        mode = DownloadMode.MERGE_KEEP,
        sourceText = answer.canonicalUrl,
        storageMode = parentSpec.storageMode,
        storageRoot = parentSpec.storageRoot,
        taskFolder = "${archive.taskFolder}/answers/${zhihuAnswerFolder(answer)}",
        authorKey = creatorRepository.upsertFromParse(result),
        batchId = parentSpec.taskId,
        creatorChild = true,
        questionArchiveId = archive.questionId,
        questionChild = true,
        zhihuCommentRequest = if (archive.includeComments) {
            ZhihuCommentRequest(answer.answerId, answer.commentCount)
        } else null,
    )

    private suspend fun waitForChildren(archive: ZhihuQuestionArchive, parentTaskId: String) {
        while (true) {
            val answers = questions.listAnswers(archive.questionId)
            val taskRecords = answers.mapNotNull { it.taskId.takeIf(String::isNotBlank) }
                .mapNotNull { tasks.get(it) }
            val active = taskRecords.count { it.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING) }
            val terminal = taskRecords.size - active + answers.count { it.taskId.isBlank() }
            val total = answers.size.coerceAtLeast(1)
            val progress = (35 + terminal * 64 / total).coerceIn(35, 99)
            tasks.update(
                parentTaskId,
                TaskStatus.RUNNING,
                if (active > 0) "正在下载回答（剩余 $active）" else "正在生成问题索引",
                progress,
            )
            if (active == 0) return
            delay(1_500L)
        }
    }

    private suspend fun synchronizeAnswerStatuses(questionId: String) {
        questions.listAnswers(questionId).forEach { answer ->
            val task = answer.taskId.takeIf(String::isNotBlank)?.let { tasks.get(it) } ?: return@forEach
            val status = when (task.status) {
                TaskStatus.COMPLETE -> ZhihuQuestionAnswerStatus.COMPLETE
                TaskStatus.FAILED, TaskStatus.CANCELLED -> ZhihuQuestionAnswerStatus.FAILED
                TaskStatus.RUNNING -> ZhihuQuestionAnswerStatus.PREPARING
                else -> ZhihuQuestionAnswerStatus.QUEUED
            }
            questions.updateAnswerStatus(
                questionId,
                answer.answerId,
                status,
                if (status == ZhihuQuestionAnswerStatus.FAILED) task.error else "",
            )
        }
    }

    private suspend fun publishIndex(
        spec: TaskSpec,
        archive: ZhihuQuestionArchive,
        answers: List<ZhihuQuestionAnswer>,
    ): TaskOutput {
        val directory = File(context.cacheDir, "questionIndexes/${spec.taskId}").apply { mkdirs() }
        val index = File(directory, "index.md")
        index.writeText(buildQuestionIndexMarkdown(archive, answers), Charsets.UTF_8)
        PublicStorage.ensureDestination(context, spec)
        val previous = tasks.get(spec.taskId)?.outputs?.firstOrNull {
            it.displayName.equals("index.md", ignoreCase = true)
        }
        val output = previous?.let { overwriteOutput(it, index) }
            ?: PublicStorage.publish(context, index, spec, "index.md")
        return output.also {
            directory.deleteRecursively()
        }
    }

    private fun overwriteOutput(output: TaskOutput, source: File): TaskOutput? = runCatching {
        val uri = Uri.parse(output.uri)
        when (uri.scheme) {
            "file" -> File(requireNotNull(uri.path)).outputStream().use { destination ->
                source.inputStream().use { it.copyTo(destination) }
            }
            else -> context.contentResolver.openOutputStream(uri, "rwt")?.use { destination ->
                source.inputStream().use { it.copyTo(destination) }
            } ?: error("无法覆盖问题索引")
        }
        output.copy(sizeBytes = source.length())
    }.getOrNull()

    private suspend fun pauseArchive(
        archive: ZhihuQuestionArchive,
        parentTaskId: String,
        message: String,
    ) {
        questions.updateStatus(archive.questionId, ZhihuQuestionStatus.PAUSED, message)
        tasks.update(parentTaskId, TaskStatus.FAILED, "因登录或风控暂停", 0, message)
    }

    private suspend fun enqueue(taskId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ZhihuQuestionArchiveWorker>()
            .setInputData(Data.Builder().putString(ZhihuQuestionArchiveWorker.KEY_TASK_ID, taskId).build())
            .addTag(taskId)
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(taskId), policy, request).await()
    }

    companion object {
        private const val NO_MORE_ANSWERS_OFFSET = -1
        fun uniqueWorkName(taskId: String) = "zhihu-question-$taskId"
    }
}

internal fun canContinueQuestionArchive(
    archive: ZhihuQuestionArchive,
    downloadedAnswers: Int,
): Boolean = archive.hasMore || (
    archive.scope == ZhihuQuestionDownloadScope.FIRST_PAGE &&
        archive.nextOffset >= 0 &&
        archive.answerCount > downloadedAnswers
    )

internal fun zhihuQuestionFolder(info: ZhihuQuestionInfo): String =
    "知乎/${sanitizeFolderSegment(info.title)}_${sanitizeFolderSegment(info.questionId)}"

internal fun zhihuAnswerFolder(answer: ZhihuQuestionAnswer): String =
    "${sanitizeFolderSegment(answer.answerId)}_${sanitizeFolderSegment(answer.author)}"

internal fun buildQuestionIndexMarkdown(
    archive: ZhihuQuestionArchive,
    answers: List<ZhihuQuestionAnswer>,
): String = buildString {
    append("# ").append(archive.title).append("\n\n")
    append("来源：[").append(archive.canonicalUrl).append("](")
        .append(archive.canonicalUrl).append(")\n\n")
    append("已归档回答：").append(answers.size).append("\n\n---\n\n")
    answers.forEachIndexed { index, answer ->
        append(index + 1).append(". [")
            .append(answer.author.ifBlank { "匿名用户" }.replace("]", "\\]"))
            .append("的回答](answers/").append(zhihuAnswerFolder(answer)).append("/answer.md)")
        if (answer.taskId.isBlank() || answer.status == ZhihuQuestionAnswerStatus.FAILED) {
            append("（下载失败）")
        }
        append('\n')
    }
}.trimEnd() + "\n"
