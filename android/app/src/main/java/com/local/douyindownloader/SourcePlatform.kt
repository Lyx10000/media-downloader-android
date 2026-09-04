package com.local.douyindownloader

import java.net.URI

enum class SourcePlatform(
    val wireValue: String,
    val displayName: String,
    val homeUrl: String,
    val referer: String,
    val anonymousFirst: Boolean,
) {
    DOUYIN(
        wireValue = "douyin",
        displayName = "抖音",
        homeUrl = "https://www.douyin.com/",
        referer = "https://www.douyin.com/",
        anonymousFirst = false,
    ),
    XIAOHONGSHU(
        wireValue = "xiaohongshu",
        displayName = "小红书",
        homeUrl = "https://www.xiaohongshu.com/",
        referer = "https://www.xiaohongshu.com/",
        anonymousFirst = true,
    );

    fun matchesHost(host: String): Boolean = when (this) {
        DOUYIN -> host == "douyin.com" || host.endsWith(".douyin.com") ||
            host == "iesdouyin.com" || host.endsWith(".iesdouyin.com")
        XIAOHONGSHU -> host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") ||
            host == "xhslink.cn" || host.endsWith(".xhslink.cn") ||
            host == "xhslink.com" || host.endsWith(".xhslink.com")
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

private val WEB_URL = Regex(
    "https?://[^\\s，。；：！？）】》]+",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_URL_PUNCTUATION = charArrayOf(
    '.', ',', ';', ':', '!', '?', ')', ']', '}',
    '。', '，', '；', '：', '！', '？', '）', '】', '》',
)
