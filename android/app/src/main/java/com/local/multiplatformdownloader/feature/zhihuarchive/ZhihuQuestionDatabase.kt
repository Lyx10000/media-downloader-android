package com.local.multiplatformdownloader.feature.zhihuarchive

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "zhihu_questions")
data class ZhihuQuestionEntity(
    @PrimaryKey @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "parent_task_id") val parentTaskId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "canonical_url") val canonicalUrl: String,
    @ColumnInfo(name = "answer_count") val answerCount: Int,
    @ColumnInfo(name = "next_offset") val nextOffset: Int,
    @ColumnInfo(name = "has_more") val hasMore: Boolean,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "include_comments") val includeComments: Boolean,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "task_folder") val taskFolder: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "refreshed_at") val refreshedAt: Long,
    @ColumnInfo(name = "error") val error: String,
)

@Entity(primaryKeys = ["question_id", "answer_id"], tableName = "zhihu_question_answers")
data class ZhihuQuestionAnswerEntity(
    @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "answer_id") val answerId: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "author") val author: String,
    @ColumnInfo(name = "excerpt") val excerpt: String,
    @ColumnInfo(name = "voteup_count") val voteupCount: Int,
    @ColumnInfo(name = "comment_count") val commentCount: Int,
    @ColumnInfo(name = "canonical_url") val canonicalUrl: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "error") val error: String,
)

@Dao
interface ZhihuQuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuestion(entity: ZhihuQuestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnswers(entities: List<ZhihuQuestionAnswerEntity>)

    @Query("SELECT * FROM zhihu_questions ORDER BY created_at DESC")
    fun observeQuestions(): Flow<List<ZhihuQuestionEntity>>

    @Query("SELECT * FROM zhihu_questions WHERE question_id = :questionId LIMIT 1")
    suspend fun getQuestion(questionId: String): ZhihuQuestionEntity?

    @Query("SELECT * FROM zhihu_questions WHERE parent_task_id = :taskId LIMIT 1")
    suspend fun getQuestionByTask(taskId: String): ZhihuQuestionEntity?

    @Query("SELECT * FROM zhihu_question_answers WHERE question_id = :questionId ORDER BY position")
    fun observeAnswers(questionId: String): Flow<List<ZhihuQuestionAnswerEntity>>

    @Query("SELECT * FROM zhihu_question_answers WHERE question_id = :questionId ORDER BY position")
    suspend fun listAnswers(questionId: String): List<ZhihuQuestionAnswerEntity>

    @Query("SELECT * FROM zhihu_question_answers WHERE question_id = :questionId AND answer_id = :answerId LIMIT 1")
    suspend fun getAnswer(questionId: String, answerId: String): ZhihuQuestionAnswerEntity?

    @Query("UPDATE zhihu_question_answers SET task_id = :taskId, status = :status, error = :error WHERE question_id = :questionId AND answer_id = :answerId")
    suspend fun updateAnswerTask(
        questionId: String,
        answerId: String,
        taskId: String,
        status: String,
        error: String,
    )

    @Query("UPDATE zhihu_question_answers SET status = :status, error = :error WHERE question_id = :questionId AND answer_id = :answerId")
    suspend fun updateAnswerStatus(questionId: String, answerId: String, status: String, error: String)

    @Query("UPDATE zhihu_questions SET next_offset = :nextOffset, has_more = :hasMore, refreshed_at = :refreshedAt WHERE question_id = :questionId")
    suspend fun updateCursor(questionId: String, nextOffset: Int, hasMore: Boolean, refreshedAt: Long)

    @Query("UPDATE zhihu_questions SET status = :status, error = :error, refreshed_at = :refreshedAt WHERE question_id = :questionId")
    suspend fun updateQuestionStatus(questionId: String, status: String, error: String, refreshedAt: Long)

    @Query("DELETE FROM zhihu_question_answers WHERE question_id = :questionId")
    suspend fun deleteAnswers(questionId: String)

    @Query("DELETE FROM zhihu_question_answers WHERE question_id = :questionId AND answer_id = :answerId")
    suspend fun deleteAnswer(questionId: String, answerId: String)

    @Query("DELETE FROM zhihu_questions WHERE question_id = :questionId")
    suspend fun deleteQuestion(questionId: String)
}

internal fun ZhihuQuestionEntity.toArchive() = ZhihuQuestionArchive(
    questionId = questionId,
    parentTaskId = parentTaskId,
    title = title,
    canonicalUrl = canonicalUrl,
    answerCount = answerCount,
    nextOffset = nextOffset,
    hasMore = hasMore,
    scope = ZhihuQuestionDownloadScope.fromWire(scope),
    includeComments = includeComments,
    status = ZhihuQuestionStatus.fromWire(status),
    taskFolder = taskFolder,
    createdAt = createdAt,
    refreshedAt = refreshedAt,
    error = error,
)

internal fun ZhihuQuestionArchive.toEntity() = ZhihuQuestionEntity(
    questionId, parentTaskId, title, canonicalUrl, answerCount, nextOffset, hasMore,
    scope.wireValue, includeComments, status.wireValue, taskFolder, createdAt, refreshedAt, error,
)

internal fun ZhihuQuestionAnswerEntity.toModel() = ZhihuQuestionAnswer(
    questionId, answerId, position, author, excerpt, voteupCount, commentCount, canonicalUrl,
    taskId, ZhihuQuestionAnswerStatus.fromWire(status), error,
)

internal fun ZhihuQuestionAnswer.toEntity() = ZhihuQuestionAnswerEntity(
    questionId, answerId, position, author, excerpt, voteupCount, commentCount, canonicalUrl,
    taskId, status.wireValue, error,
)
