package com.local.multiplatformdownloader.core.model

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
        loginUrl = "https://www.xiaohongshu.com/login",
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
    ),
    X(
        wireValue = "x",
        displayName = "X",
        homeUrl = "https://x.com/",
        loginUrl = "https://x.com/i/flow/login",
        referer = "https://x.com/",
        anonymousFirst = true,
    ),
    INSTAGRAM(
        wireValue = "instagram",
        displayName = "Instagram",
        homeUrl = "https://www.instagram.com/",
        loginUrl = "https://www.instagram.com/accounts/login/",
        referer = "https://www.instagram.com/",
        anonymousFirst = true,
    ),
    BILIBILI(
        wireValue = "bilibili",
        displayName = "B站",
        homeUrl = "https://www.bilibili.com/",
        loginUrl = "https://passport.bilibili.com/h5-app/passport/login",
        referer = "https://www.bilibili.com/",
        anonymousFirst = true,
    );

    fun matchesHost(host: String): Boolean = when (this) {
        DOUYIN -> host == "douyin.com" || host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" || host.endsWith(".iesdouyin.com")
        XIAOHONGSHU -> host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") ||
            host == "xhslink.cn" || host.endsWith(".xhslink.cn") ||
            host == "xhslink.com" || host.endsWith(".xhslink.com")
        ZHIHU -> host == "zhihu.com" || host.endsWith(".zhihu.com")
        X -> host == "x.com" || host.endsWith(".x.com") ||
            host == "twitter.com" || host.endsWith(".twitter.com")
        INSTAGRAM -> host == "instagram.com" || host.endsWith(".instagram.com")
        BILIBILI -> host in setOf(
            "bilibili.com", "www.bilibili.com", "m.bilibili.com", "b23.tv",
            "passport.bilibili.com", "space.bilibili.com", "live.bilibili.com", "t.bilibili.com",
        )
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
    .firstOrNull() ?: text.trim().takeIf { BILIBILI_BARE_ID.matches(it) }?.let {
        SupportedSource(SourcePlatform.BILIBILI, "https://www.bilibili.com/video/$it")
    }

private val BILIBILI_BARE_ID = Regex("BV[A-Za-z0-9]{10}|[aA][vV][1-9][0-9]{0,18}")

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

internal fun upgradePlatformCleartextUrl(url: String, platform: SourcePlatform): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true) || !platform.matchesHost(uri.host.orEmpty())) {
        return null
    }
    return runCatching {
        val securePort = if (uri.port == 80) -1 else uri.port
        URI("https", uri.userInfo, uri.host, securePort, uri.path, uri.query, uri.fragment).toString()
    }.getOrNull()
}

private val WEB_URL = Regex(
    "https?://[^\\s，。；：！？）】》]+",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_URL_PUNCTUATION = charArrayOf(
    '.', ',', ';', ':', '!', '?', ')', ']', '}',
    '。', '，', '；', '：', '！', '？', '）', '】', '》',
)
