package com.local.douyindownloader

import org.json.JSONObject

enum class ZhihuQuestionDownloadScope(val wireValue: String) {
    FIRST_PAGE("first_page"),
    ALL("all");

    companion object {
        fun fromWire(value: String): ZhihuQuestionDownloadScope = entries.firstOrNull {
            it.wireValue == value
        } ?: ALL
    }
}

data class ZhihuQuestionInfo(
    val questionId: String,
    val title: String,
    val canonicalUrl: String,
    val answerCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("question_id", questionId)
        put("title", title)
        put("canonical_url", canonicalUrl)
        put("answer_count", answerCount)
    }

    companion object {
        fun fromJson(value: JSONObject) = ZhihuQuestionInfo(
            questionId = value.optString("question_id"),
            title = value.optString("title"),
            canonicalUrl = value.optString("canonical_url"),
            answerCount = value.optInt("answer_count").coerceAtLeast(0),
        )
    }
}

data class ZhihuCommentRequest(
    val answerId: String,
    val expectedCount: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("answer_id", answerId)
        put("expected_count", expectedCount)
    }

    companion object {
        fun fromJson(value: JSONObject) = ZhihuCommentRequest(
            answerId = value.optString("answer_id"),
            expectedCount = value.optInt("expected_count").coerceAtLeast(0),
        )
    }
}

enum class ZhihuQuestionStatus(val wireValue: String) {
    QUEUED("QUEUED"), RUNNING("RUNNING"), PAUSED("PAUSED"), COMPLETE("COMPLETE"),
    PARTIAL("PARTIAL"), FAILED("FAILED"), CANCELLED("CANCELLED");

    companion object {
        fun fromWire(value: String): ZhihuQuestionStatus = entries.firstOrNull {
            it.wireValue == value
        } ?: QUEUED
    }
}

enum class ZhihuQuestionAnswerStatus(val wireValue: String) {
    DISCOVERED("DISCOVERED"), PREPARING("PREPARING"), QUEUED("QUEUED"),
    COMPLETE("COMPLETE"), FAILED("FAILED"), PAUSED("PAUSED");

    companion object {
        fun fromWire(value: String): ZhihuQuestionAnswerStatus = entries.firstOrNull {
            it.wireValue == value
        } ?: DISCOVERED
    }
}

data class ZhihuQuestionArchive(
    val questionId: String,
    val parentTaskId: String,
    val title: String,
    val canonicalUrl: String,
    val answerCount: Int,
    val nextOffset: Int,
    val hasMore: Boolean,
    val scope: ZhihuQuestionDownloadScope,
    val includeComments: Boolean,
    val status: ZhihuQuestionStatus,
    val taskFolder: String,
    val createdAt: Long,
    val refreshedAt: Long,
    val error: String = "",
)

data class ZhihuQuestionAnswer(
    val questionId: String,
    val answerId: String,
    val position: Int,
    val author: String,
    val excerpt: String,
    val voteupCount: Int,
    val commentCount: Int,
    val canonicalUrl: String,
    val taskId: String = "",
    val status: ZhihuQuestionAnswerStatus = ZhihuQuestionAnswerStatus.DISCOVERED,
    val error: String = "",
)

data class ZhihuQuestionPage(
    val answers: List<ZhihuQuestionAnswer>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

internal fun zhihuQuestionKey(questionId: String): String = "zhihu-question:${questionId.trim()}"
