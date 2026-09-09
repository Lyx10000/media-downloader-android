package com.local.multiplatformdownloader.platform.common

import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.model.extractSupportedSource
import com.local.multiplatformdownloader.platform.bilibili.BilibiliPlatformParser
import com.local.multiplatformdownloader.platform.douyin.DouyinPlatformParser
import com.local.multiplatformdownloader.platform.instagram.InstagramPlatformParser
import com.local.multiplatformdownloader.platform.x.XPlatformParser
import com.local.multiplatformdownloader.platform.xiaohongshu.XiaohongshuPlatformParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuPlatformParser
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

internal interface PlatformParser {
    val platform: SourcePlatform
    fun parse(
        shareText: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot? = null,
    ): ParseResult
}

@Singleton
class KotlinParserRouter @Inject internal constructor(
    douyinParser: DouyinPlatformParser,
    xiaohongshuParser: XiaohongshuPlatformParser,
    zhihuParser: ZhihuPlatformParser,
    xParser: XPlatformParser,
    instagramParser: InstagramPlatformParser,
    bilibiliParser: BilibiliPlatformParser,
) {
    private val parsers = listOf(douyinParser, xiaohongshuParser, zhihuParser, xParser, instagramParser, bilibiliParser)
        .associateBy(PlatformParser::platform)

    internal fun parse(
        shareText: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot? = null,
    ): ParseResult {
        val source = extractSupportedSource(shareText)
            ?: return parseFailure(SourcePlatform.DOUYIN, "UNSUPPORTED_URL", "没有找到支持的作品链接")
        return parsers.getValue(source.platform).parse(shareText, cookieHeader, pageSnapshot)
    }
}

internal object MediaSizeHydrator {
    fun hydrate(variants: List<MediaVariant>, probe: (List<String>) -> Long): List<MediaVariant> {
        val candidates = variants.mapIndexedNotNull { index, variant ->
            index.takeIf { variant.sizeSource != "api" && variant.urls.isNotEmpty() }
        }
        if (candidates.isEmpty()) return variants
        val executor = Executors.newFixedThreadPool(minOf(4, candidates.size))
        return try {
            val futures = candidates.associateWith { index ->
                executor.submit<Long> { runCatching { probe(variants[index].urls) }.getOrDefault(0L) }
            }
            variants.mapIndexed { index, variant ->
                val size = futures[index]?.get() ?: 0L
                if (size > 0) variant.copy(size = size, sizeSource = "cdn") else variant
            }
        } finally {
            executor.shutdownNow()
        }
    }
}

internal fun parseFailure(platform: SourcePlatform, code: String, message: String): ParseResult =
    ParseResult(ok = false, platform = platform, errorCode = code, message = message)
