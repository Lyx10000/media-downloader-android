package com.local.multiplatformdownloader.feature.zhihuarchive

import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.feature.creator.toEntity

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
internal class ZhihuQuestionRepository @Inject constructor(
    private val dao: ZhihuQuestionDao,
) {
    fun observeQuestions(): Flow<List<ZhihuQuestionArchive>> = dao.observeQuestions().map { list ->
        list.map(ZhihuQuestionEntity::toArchive)
    }

    fun observeAnswers(questionId: String): Flow<List<ZhihuQuestionAnswer>> =
        dao.observeAnswers(questionId).map { list -> list.map(ZhihuQuestionAnswerEntity::toModel) }

    suspend fun get(questionId: String): ZhihuQuestionArchive? =
        dao.getQuestion(questionId)?.toArchive()

    suspend fun getByTask(taskId: String): ZhihuQuestionArchive? =
        dao.getQuestionByTask(taskId)?.toArchive()

    suspend fun upsert(archive: ZhihuQuestionArchive) = dao.upsertQuestion(archive.toEntity())

    suspend fun savePage(questionId: String, page: ZhihuQuestionPage) {
        val existing = dao.listAnswers(questionId).associateBy(ZhihuQuestionAnswerEntity::answerId)
        if (page.answers.isNotEmpty()) {
            dao.upsertAnswers(page.answers.map { answer ->
                val previous = existing[answer.answerId]
                answer.copy(
                    taskId = previous?.taskId.orEmpty(),
                    status = previous?.status?.let(ZhihuQuestionAnswerStatus::fromWire)
                        ?: answer.status,
                    error = previous?.error.orEmpty(),
                ).toEntity()
            })
        }
        dao.updateCursor(questionId, page.nextOffset, page.hasMore, System.currentTimeMillis())
    }

    suspend fun listAnswers(questionId: String): List<ZhihuQuestionAnswer> =
        dao.listAnswers(questionId).map(ZhihuQuestionAnswerEntity::toModel)

    suspend fun updateAnswer(
        questionId: String,
        answerId: String,
        taskId: String,
        status: ZhihuQuestionAnswerStatus,
        error: String = "",
    ) = dao.updateAnswerTask(
        questionId,
        answerId,
        taskId,
        status.wireValue,
        Redactor.sanitize(error),
    )

    suspend fun updateAnswerStatus(
        questionId: String,
        answerId: String,
        status: ZhihuQuestionAnswerStatus,
        error: String = "",
    ) = dao.updateAnswerStatus(questionId, answerId, status.wireValue, Redactor.sanitize(error))

    suspend fun updateStatus(
        questionId: String,
        status: ZhihuQuestionStatus,
        error: String = "",
    ) = dao.updateQuestionStatus(
        questionId,
        status.wireValue,
        Redactor.sanitize(error),
        System.currentTimeMillis(),
    )

    suspend fun delete(questionId: String) {
        dao.deleteAnswers(questionId)
        dao.deleteQuestion(questionId)
    }

    suspend fun deleteAnswer(questionId: String, answerId: String) {
        dao.deleteAnswer(questionId, answerId)
    }
}
