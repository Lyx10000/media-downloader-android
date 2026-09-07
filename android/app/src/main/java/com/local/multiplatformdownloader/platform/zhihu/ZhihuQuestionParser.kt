package com.local.multiplatformdownloader.platform.zhihu

import com.local.multiplatformdownloader.core.model.DocumentContent
import com.local.multiplatformdownloader.core.model.DocumentType
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionAnswer
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionInfo
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionPage
import com.local.multiplatformdownloader.core.network.responseShape
import com.local.multiplatformdownloader.platform.common.PlatformParseException

import java.net.URI
import org.json.JSONObject
import org.jsoup.Jsoup

internal object ZhihuQuestionParser {
    fun normalizeQuestion(payload: JSONObject, source: ResolvedZhihuSource): ParseResult {
        val title = payload.optString("title").trim()
        if (title.isBlank()) throw PlatformParseException("DETAIL_EMPTY", "知乎问题标题为空")
        val answerCount = payload.optInt("answer_count", payload.optInt("answerCount"))
            .coerceAtLeast(0)
        val info = ZhihuQuestionInfo(
            questionId = source.questionId.ifBlank { source.contentId },
            title = title,
            canonicalUrl = source.canonicalUrl,
            answerCount = answerCount,
        )
        return ParseResult(
            ok = true,
            platform = SourcePlatform.ZHIHU,
            contentId = info.questionId,
            canonicalUrl = info.canonicalUrl,
            referer = info.canonicalUrl,
            kind = MediaKind.DOCUMENT,
            description = title,
            document = DocumentContent(
                type = DocumentType.QUESTION,
                title = title,
                author = "",
                sourceUrl = info.canonicalUrl,
                blocks = emptyList(),
                assets = emptyList(),
            ),
            question = info,
            responseShape = JSONObject().apply {
                put("type", payload.optString("type", "question"))
                put("answer_count", answerCount)
            }.toString(),
        )
    }

    fun normalizePage(
        payload: JSONObject,
        questionId: String,
        requestedOffset: Int,
    ): ZhihuQuestionPage {
        val answers = payload.optJSONArray("data")?.let { data ->
            (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val answerId = item.opt("id")?.toString().orEmpty()
                if (answerId.isBlank()) return@mapNotNull null
                val author = item.optJSONObject("author")
                val excerpt = item.optString("excerpt").ifBlank {
                    item.optString("content")
                }.toPlainText()
                ZhihuQuestionAnswer(
                    questionId = questionId,
                    answerId = answerId,
                    position = requestedOffset + index + 1,
                    author = author?.optString("name").orEmpty().ifBlank { "匿名用户" },
                    excerpt = excerpt,
                    voteupCount = item.optInt("voteup_count").coerceAtLeast(0),
                    commentCount = item.optInt("comment_count").coerceAtLeast(0),
                    canonicalUrl = "https://www.zhihu.com/question/$questionId/answer/$answerId",
                )
            }
        }.orEmpty()
        val paging = payload.optJSONObject("paging")
        val hasMore = paging?.optBoolean("is_end", answers.isEmpty()) == false
        val fallbackOffset = requestedOffset + answers.size
        val nextOffset = paging?.optString("next")
            ?.let(::offsetFromUrl)
            ?.takeIf { it > requestedOffset }
            ?: fallbackOffset
        return ZhihuQuestionPage(answers, nextOffset, hasMore)
    }

    private fun offsetFromUrl(url: String): Int? = runCatching {
        URI(url).rawQuery.orEmpty().split('&').firstNotNullOfOrNull { part ->
            part.substringBefore('=').takeIf { it == "offset" }
                ?.let { part.substringAfter('=', "").toIntOrNull() }
        }
    }.getOrNull()

    private fun String.toPlainText(): String = if ('<' in this && '>' in this) {
        Jsoup.parseBodyFragment(this).text().trim()
    } else trim()
}
