package com.local.multiplatformdownloader.platform.zhihu


import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionPage
import com.local.multiplatformdownloader.platform.common.ZhihuPlatformParser
import com.local.multiplatformdownloader.platform.common.PlatformParseException

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
internal class ZhihuQuestionSource @Inject constructor(
    private val http: ParserHttpClient,
) {
    fun fetchPage(
        questionId: String,
        offset: Int,
        cookieHeader: String,
        limit: Int = PAGE_SIZE,
    ): ZhihuQuestionPage {
        val include = encode(
            "data[*].id,excerpt,content,voteup_count,comment_count,author.name,author.url_token",
        )
        val url = "https://www.zhihu.com/api/v4/questions/$questionId/answers" +
            "?include=$include&limit=$limit&offset=${offset.coerceAtLeast(0)}&sort_by=default"
        val referer = "https://www.zhihu.com/question/$questionId"
        val response = http.get(url, headers(referer), cookieHeader, 30)
        when (response.statusCode) {
            in 200..299 -> Unit
            401, 403, 429 -> throw PlatformParseException(
                "AUTH_OR_RISK",
                "知乎认证或风控拒绝了回答列表请求",
                response.statusCode,
            )
            404, 410 -> throw PlatformParseException(
                "CONTENT_UNAVAILABLE",
                "知乎问题不存在或已不可访问",
                response.statusCode,
            )
            else -> throw PlatformParseException(
                "HTTP_ERROR",
                "知乎回答列表请求失败（HTTP ${response.statusCode}）",
                response.statusCode,
            )
        }
        return runCatching {
            ZhihuQuestionParser.normalizePage(JSONObject(response.body), questionId, offset)
        }.getOrElse { error ->
            if (error is PlatformParseException) throw error
            throw PlatformParseException("DETAIL_EMPTY", "知乎回答列表格式发生变化")
        }
    }

    private fun headers(referer: String) = mapOf(
        "User-Agent" to ZhihuPlatformParser.USER_AGENT,
        "Referer" to referer,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

    private fun encode(value: String): String = URLEncoder.encode(
        value,
        StandardCharsets.UTF_8.name(),
    )

    companion object {
        const val PAGE_SIZE = 20
    }
}
