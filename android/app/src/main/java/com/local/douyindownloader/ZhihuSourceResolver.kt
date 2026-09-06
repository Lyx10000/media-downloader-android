package com.local.douyindownloader

import java.net.URI

internal enum class ZhihuContentType {
    QUESTION,
    ARTICLE,
    ANSWER,
    PIN,
    VIDEO,
}

internal data class ResolvedZhihuSource(
    val type: ZhihuContentType,
    val contentId: String,
    val canonicalUrl: String,
    val questionId: String = "",
)

internal object ZhihuSourceResolver {
    fun resolve(url: String): ResolvedZhihuSource {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw PlatformParseException("UNSUPPORTED_URL", "知乎链接格式无效")
        if (SourcePlatform.fromUrl(url) != SourcePlatform.ZHIHU) {
            throw PlatformParseException("UNSUPPORTED_URL", "没有找到支持的知乎链接")
        }
        val path = uri.path.orEmpty().trimEnd('/')

        ANSWER.matchEntire(path)?.let { match ->
            val questionId = match.groupValues[1]
            val answerId = match.groupValues[2]
            return ResolvedZhihuSource(
                type = ZhihuContentType.ANSWER,
                contentId = answerId,
                questionId = questionId,
                canonicalUrl = "https://www.zhihu.com/question/$questionId/answer/$answerId",
            )
        }
        TARDIS_ANSWER.matchEntire(path)?.let { match ->
            val answerId = match.groupValues[1]
            return ResolvedZhihuSource(
                type = ZhihuContentType.ANSWER,
                contentId = answerId,
                canonicalUrl = "https://www.zhihu.com/answer/$answerId",
            )
        }
        ARTICLE.matchEntire(path)?.let { match ->
            val articleId = match.groupValues[1]
            return article(articleId)
        }
        TARDIS_ARTICLE.matchEntire(path)?.let { match -> return article(match.groupValues[1]) }
        PIN.matchEntire(path)?.let { match ->
            val pinId = match.groupValues[1]
            return ResolvedZhihuSource(
                type = ZhihuContentType.PIN,
                contentId = pinId,
                canonicalUrl = "https://www.zhihu.com/pin/$pinId",
            )
        }
        VIDEO.matchEntire(path)?.let { match ->
            val videoId = match.groupValues[1]
            return ResolvedZhihuSource(
                type = ZhihuContentType.VIDEO,
                contentId = videoId,
                canonicalUrl = "https://www.zhihu.com/zvideo/$videoId",
            )
        }
        QUESTION.matchEntire(path)?.let { match ->
            val questionId = match.groupValues[1]
            return ResolvedZhihuSource(
                type = ZhihuContentType.QUESTION,
                contentId = questionId,
                questionId = questionId,
                canonicalUrl = "https://www.zhihu.com/question/$questionId",
            )
        }
        throw PlatformParseException("UNSUPPORTED_URL", "暂不支持该知乎内容地址")
    }

    private fun article(articleId: String) = ResolvedZhihuSource(
        type = ZhihuContentType.ARTICLE,
        contentId = articleId,
        canonicalUrl = "https://zhuanlan.zhihu.com/p/$articleId",
    )

    private val ANSWER = Regex("/question/(\\d+)/answer/(\\d+)")
    private val TARDIS_ANSWER = Regex("/(?:answer|tardis/zm/ans)/(\\d+)")
    private val ARTICLE = Regex("/p/(\\d+)")
    private val TARDIS_ARTICLE = Regex("/tardis/zm/art/(\\d+)")
    private val PIN = Regex("/pin/(\\d+)")
    private val VIDEO = Regex("/zvideo/(\\d+)")
    private val QUESTION = Regex("/question/(\\d+)")
}
