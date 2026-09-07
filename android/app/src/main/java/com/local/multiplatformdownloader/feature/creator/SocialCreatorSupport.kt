package com.local.multiplatformdownloader.feature.creator


import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.core.network.values

import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

/** An exact handle, never a display-name search or a post URL. */
internal fun socialCreatorHandle(platform: SourcePlatform, query: String): String {
    val value = query.trim()
    val handle = if (value.startsWith("https://") || value.startsWith("http://")) {
        val uri = runCatching { URI(value) }.getOrNull()
        val parts = uri?.path.orEmpty().trim('/').split('/')
        val suffixes = if (platform == SourcePlatform.X) setOf("media") else setOf("reels")
        if (SourcePlatform.fromUrl(value) != platform || uri?.userInfo != null ||
            parts.size !in 1..2 || parts.size == 2 && parts[1] !in suffixes) {
            throw CreatorSourceException("AUTHOR_QUERY_UNSUPPORTED", "请提供作者主页链接，不是单条作品链接")
        }
        parts.first()
    } else value.removePrefix("@")
    val pattern = if (platform == SourcePlatform.X) Regex("[A-Za-z0-9_]{1,15}") else Regex("[A-Za-z0-9_.]{1,30}")
    val reserved = setOf("home", "explore", "search", "i", "intent", "settings", "login", "accounts", "direct",
        "p", "reel", "reels", "tv", "stories", "notifications", "messages", "share", "about", "privacy", "terms")
    if (!pattern.matches(handle) || handle.lowercase() in reserved) {
        throw CreatorSourceException("AUTHOR_QUERY_UNSUPPORTED", "请输入准确的 @用户名或作者主页链接，不支持昵称搜索")
    }
    return handle
}

internal fun socialCookie(cookie: String, name: String): String = cookie.split(';')
    .map(String::trim).firstOrNull { it.substringBefore('=') == name }?.substringAfter('=').orEmpty()

internal fun socialQuery(values: Map<String, String>): String = values.entries.joinToString("&") {
    "${it.key}=${URLEncoder.encode(it.value, "UTF-8") }"
}

internal fun socialCreatorJson(response: ParserHttpResponse, platform: SourcePlatform): JSONObject {
    val label = platform.displayName
    when (response.statusCode) {
        401, 403 -> throw CreatorSourceException("LOGIN_REQUIRED", "$label 作者接口拒绝访问，请确认登录状态或稍后重试（HTTP ${response.statusCode}）")
        429, 461 -> throw CreatorSourceException("AUTH_OR_RISK", "$label 请求受到限制，请稍后重试（HTTP ${response.statusCode}）")
        404, 410 -> throw CreatorSourceException("AUTHOR_NOT_FOUND", "$label 作者或接口暂不可访问（HTTP ${response.statusCode}），不能据此判断账号已注销")
        !in 200..299 -> throw CreatorSourceException("HTTP_ERROR", "$label 作者请求失败（HTTP ${response.statusCode}）")
    }
    val path = runCatching { URI(response.finalUrl).path }.getOrDefault("").orEmpty()
    if (path.contains("login") || path.contains("challenge")) {
        throw CreatorSourceException("LOGIN_REQUIRED", "请在首页打开 $label 登录环境完成登录或验证")
    }
    val root = runCatching { JSONObject(response.body.removePrefix("for (;;);")) }.getOrNull()
        ?: throw CreatorSourceException("RESPONSE_CHANGED", "$label 未返回作者数据，请检查登录状态；已缓存内容保留")
    val message = root.optString("message").lowercase()
    if (root.has("challenge") || message.contains("login_required") || message.contains("checkpoint")) {
        throw CreatorSourceException("LOGIN_REQUIRED", "请在首页打开 $label 登录环境完成验证")
    }
    val errors = root.optJSONArray("errors")
    if (errors != null && errors.length() > 0 || root.optString("status") == "fail") {
        val codes = errors?.values().orEmpty().mapNotNull { (it as? JSONObject)?.optInt("code") }
        val code = when {
            88 in codes || 353 in codes || message.contains("wait") -> "AUTH_OR_RISK"
            32 in codes || 89 in codes || 215 in codes -> "LOGIN_REQUIRED"
            else -> "RESPONSE_CHANGED"
        }
        // Do not put returned HTML, tokens or arbitrary server error text into logs.
        throw CreatorSourceException(code, "$label 作者接口未返回完整结果（错误码 ${codes.joinToString().ifBlank { "unknown" }}），请稍后重试")
    }
    return root
}

internal fun socialMetric(label: String, value: Any?): CreatorMetric? =
    value?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
        ?.let { CreatorMetric(label, it) }

internal const val SOCIAL_CREATOR_UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
