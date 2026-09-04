package com.local.douyindownloader

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

data class WebPageSnapshot(
    val finalUrl: String,
    val initialData: String = "",
    val title: String = "",
    val author: String = "",
    val contentHtml: String = "",
    val visibleText: String = "",
) {
    companion object {
        fun fromJavascriptResult(value: String?): WebPageSnapshot? {
            if (value.isNullOrBlank() || value == "null") return null
            val decoded = runCatching { JSONArray("[$value]").optString(0) }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: return null
            val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
            return WebPageSnapshot(
                finalUrl = root.optString("finalUrl"),
                initialData = root.optString("initialData"),
                title = root.optString("title"),
                author = root.optString("author"),
                contentHtml = root.optString("contentHtml"),
                visibleText = root.optString("visibleText"),
            ).takeIf { it.finalUrl.isNotBlank() }
        }
    }
}

internal object ZhihuWebSnapshotExtractor {
    fun extract(snapshot: WebPageSnapshot, source: ResolvedZhihuSource): JSONObject {
        val finalUri = runCatching { URI(snapshot.finalUrl) }.getOrNull()
        val finalHost = finalUri?.host.orEmpty().lowercase()
        val finalPath = finalUri?.path.orEmpty().lowercase()
        val visible = snapshot.visibleText.take(8_000)
        if (!SourcePlatform.ZHIHU.matchesHost(finalHost) ||
            finalPath.contains("signin") || finalPath.contains("account")) {
            throw PlatformParseException("LOGIN_REQUIRED", "知乎页面要求登录或人机验证，请返回首页点击知乎登录状态")
        }

        snapshot.initialData.takeIf(String::isNotBlank)?.let { initialData ->
            val entityName = when (source.type) {
                ZhihuContentType.ARTICLE -> "articles"
                ZhihuContentType.ANSWER -> "answers"
                ZhihuContentType.PIN -> "pins"
                ZhihuContentType.VIDEO -> error("独立视频不使用页面快照")
            }
            ZhihuPageStateExtractor.findEntityFromJson(initialData, entityName, source.contentId)?.let {
                return it
            }
        }

        if (source.contentId !in finalPath) {
            throw PlatformParseException("DETAIL_EMPTY", "知乎页面跳转后没有停留在目标内容")
        }
        if (snapshot.contentHtml.isBlank() && BLOCKED_PAGE.containsMatchIn(visible)) {
            throw PlatformParseException("LOGIN_REQUIRED", "知乎页面要求登录或人机验证，请返回首页点击知乎登录状态")
        }
        if (snapshot.contentHtml.isBlank()) {
            throw PlatformParseException("DETAIL_EMPTY", "知乎页面已打开，但没有提取到目标正文")
        }
        return JSONObject().apply {
            put("id", source.contentId)
            put("title", snapshot.title)
            put("author", JSONObject().put("name", snapshot.author))
            put("content", snapshot.contentHtml)
        }
    }

    private val BLOCKED_PAGE = Regex(
        "登录知乎|注册知乎|安全验证|验证码|异常请求|访问受限|请求存在异常|请完成验证",
        RegexOption.IGNORE_CASE,
    )
}
