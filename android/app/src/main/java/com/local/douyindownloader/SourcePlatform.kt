package com.local.douyindownloader

import java.net.URI

enum class SourcePlatform(
    val wireValue: String,
    val displayName: String,
    val homeUrl: String,
    val loginUrl: String,
    val referer: String,
    val anonymousFirst: Boolean,
) {
    DOUYIN(
        wireValue = "douyin",
        displayName = "抖音",
        homeUrl = "https://www.douyin.com/",
        loginUrl = "https://www.douyin.com/",
        referer = "https://www.douyin.com/",
        anonymousFirst = false,
    ),
    XIAOHONGSHU(
        wireValue = "xiaohongshu",
        displayName = "小红书",
        homeUrl = "https://www.xiaohongshu.com/",
        loginUrl = "https://www.xiaohongshu.com/",
        referer = "https://www.xiaohongshu.com/",
        anonymousFirst = true,
    ),
    ZHIHU(
        wireValue = "zhihu",
        displayName = "知乎",
        homeUrl = "https://www.zhihu.com/",
        loginUrl = "https://www.zhihu.com/signin?next=%2F",
        referer = "https://www.zhihu.com/",
        anonymousFirst = true,
    );

    fun matchesHost(host: String): Boolean = when (this) {
        DOUYIN -> host == "douyin.com" || host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" || host.endsWith(".iesdouyin.com")
        XIAOHONGSHU -> host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") ||
            host == "xhslink.cn" || host.endsWith(".xhslink.cn") ||
            host == "xhslink.com" || host.endsWith(".xhslink.com")
        ZHIHU -> host == "zhihu.com" || host.endsWith(".zhihu.com")
    }

    companion object {
        fun fromWire(value: String): SourcePlatform = entries.firstOrNull {
            it.wireValue.equals(value, ignoreCase = true)
        } ?: DOUYIN

        fun fromUrl(url: String): SourcePlatform? {
            val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            return entries.firstOrNull { it.matchesHost(host) }
        }
    }
}

internal data class SupportedSource(
    val platform: SourcePlatform,
    val url: String,
)

internal fun extractSupportedSource(text: String): SupportedSource? = WEB_URL.findAll(text)
    .map { it.value.trimEnd(*TRAILING_URL_PUNCTUATION) }
    .mapNotNull { url -> SourcePlatform.fromUrl(url)?.let { SupportedSource(it, url) } }
    .firstOrNull()

internal fun isPlatformPage(url: String, platform: SourcePlatform): Boolean =
    SourcePlatform.fromUrl(url) == platform

internal enum class WebNavigationTarget {
    WEB,
    EXTERNAL_APP,
    BLOCKED,
}

internal fun classifyWebNavigation(url: String): WebNavigationTarget {
    val scheme = runCatching { URI(url).scheme.orEmpty().lowercase() }.getOrDefault("")
    return when (scheme) {
        "http", "https", "about", "data", "blob" -> WebNavigationTarget.WEB
        "", "javascript", "file", "content" -> WebNavigationTarget.BLOCKED
        else -> WebNavigationTarget.EXTERNAL_APP
    }
}

private val WEB_URL = Regex(
    "https?://[^\\s，。；：！？）】》]+",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_URL_PUNCTUATION = charArrayOf(
    '.', ',', ';', ':', '!', '?', ')', ']', '}',
    '。', '，', '；', '：', '！', '？', '）', '】', '》',
)
