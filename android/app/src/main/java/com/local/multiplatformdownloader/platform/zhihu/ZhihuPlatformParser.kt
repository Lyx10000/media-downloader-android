package com.local.multiplatformdownloader.platform.zhihu

import com.local.multiplatformdownloader.core.network.stableDistinct

import com.local.multiplatformdownloader.core.model.DocumentAssetKind
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.ParserAttempt
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.platform.zhihu.ZhihuWebSnapshotExtractor
import com.local.multiplatformdownloader.core.model.extractSupportedSource
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.core.network.ParserHttpStatusException
import com.local.multiplatformdownloader.platform.bilibili.BilibiliPlatformParser
import com.local.multiplatformdownloader.platform.douyin.ABogusSigner
import com.local.multiplatformdownloader.platform.douyin.DouyinFallbackExtractor
import com.local.multiplatformdownloader.platform.douyin.DouyinMediaNormalizer
import com.local.multiplatformdownloader.platform.instagram.InstagramPlatformParser
import com.local.multiplatformdownloader.platform.x.XPlatformParser
import com.local.multiplatformdownloader.platform.xiaohongshu.XiaohongshuMediaParser
import com.local.multiplatformdownloader.platform.zhihu.ResolvedZhihuSource
import com.local.multiplatformdownloader.platform.zhihu.ZhihuContentType
import com.local.multiplatformdownloader.platform.zhihu.ZhihuMediaParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuPageStateExtractor
import com.local.multiplatformdownloader.platform.zhihu.ZhihuQuestionParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuSourceResolver

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import com.local.multiplatformdownloader.platform.common.MediaSizeHydrator
import com.local.multiplatformdownloader.platform.common.PlatformParseException
import com.local.multiplatformdownloader.platform.common.PlatformParser
import com.local.multiplatformdownloader.platform.common.parseFailure

internal class ZhihuPlatformParser @Inject constructor(
    private val httpClient: ParserHttpClient,
) : PlatformParser {
    override val platform = SourcePlatform.ZHIHU

    override fun parse(
        shareText: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): ParseResult = try {
        val sourceUrl = extractSupportedSource(shareText)
            ?.takeIf { it.platform == platform }
            ?.url
            ?: throw PlatformParseException("UNSUPPORTED_URL", "没有找到知乎链接")
        val source = ZhihuSourceResolver.resolve(sourceUrl)
        if (source.type == ZhihuContentType.QUESTION) {
            ZhihuQuestionParser.normalizeQuestion(fetchQuestion(source, cookieHeader, pageSnapshot), source)
        } else if (source.type == ZhihuContentType.VIDEO) {
            val payload = fetchStandaloneVideo(source, cookieHeader)
            hydrateSizes(
                ZhihuMediaParser.normalizeStandaloneVideo(payload, source.contentId, source.canonicalUrl),
            )
        } else {
            val payload = if (pageSnapshot != null) {
                ZhihuWebSnapshotExtractor.extract(pageSnapshot, source)
            } else {
                fetchDocument(source, cookieHeader)
            }
            hydrateSizes(
                ZhihuMediaParser.normalizeDocument(payload, source) { videoId ->
                    fetchLensVideo(videoId, source.canonicalUrl, cookieHeader)
                },
            )
        }
    } catch (error: PlatformParseException) {
        parseFailure(platform, error.code, error.message.orEmpty()).copy(
            parserAttempts = error.statusCode.takeIf { it > 0 }?.let { status ->
                listOf(ParserAttempt("request_failed", false, status, error.code))
            }.orEmpty(),
        )
    } catch (error: IOException) {
        parseFailure(platform, "NETWORK", "网络请求失败：${error.javaClass.simpleName}")
    } catch (error: Exception) {
        parseFailure(platform, "PARSE_FAILED", error.message ?: error.javaClass.simpleName)
    }

    private fun fetchStandaloneVideo(
        source: ResolvedZhihuSource,
        cookieHeader: String,
    ): JSONObject {
        val endpoints = listOf(
            "https://api.zhihu.com/zvideos/${source.contentId}",
            "https://www.zhihu.com/api/v4/zvideos/${source.contentId}",
        )
        var lastStatus = 0
        endpoints.forEach { endpoint ->
            val response = request(endpoint, source.canonicalUrl, cookieHeader)
            lastStatus = response.statusCode
            if (response.statusCode in 200..299 && response.body.isNotBlank()) {
                return JSONObject(response.body)
            }
        }
        throw statusError(lastStatus)
    }

    private fun fetchDocument(source: ResolvedZhihuSource, cookieHeader: String): JSONObject {
        val (apiName, entityName) = when (source.type) {
            ZhihuContentType.QUESTION -> error("问题使用独立解析路径")
            ZhihuContentType.ARTICLE -> "articles" to "articles"
            ZhihuContentType.ANSWER -> "answers" to "answers"
            ZhihuContentType.PIN -> "pins" to "pins"
            ZhihuContentType.VIDEO -> error("不可达的视频文档类型")
        }
        val api = "https://www.zhihu.com/api/v4/$apiName/${source.contentId}?include=content"
        val apiResponse = request(api, source.canonicalUrl, cookieHeader)
        if (apiResponse.statusCode in 200..299 && apiResponse.body.isNotBlank()) {
            return JSONObject(apiResponse.body)
        }

        val pageResponse = request(source.canonicalUrl, SourcePlatform.ZHIHU.referer, cookieHeader, html = true)
        if (pageResponse.statusCode !in 200..299) {
            throw statusError(pageResponse.statusCode)
        }
        ZhihuPageStateExtractor.findEntity(pageResponse.body, entityName, source.contentId)
            ?.let { return it }
        if (RESTRICTED.containsMatchIn(pageResponse.body)) {
            throw PlatformParseException("CONTENT_RESTRICTED", "当前知乎内容需要登录、付费或额外权限")
        }
        throw PlatformParseException("DETAIL_EMPTY", "知乎页面中没有找到目标内容")
    }

    private fun fetchQuestion(
        source: ResolvedZhihuSource,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): JSONObject {
        pageSnapshot?.initialData?.takeIf(String::isNotBlank)?.let { initialData ->
            ZhihuPageStateExtractor.findEntityFromJson(
                initialData,
                "questions",
                source.contentId,
            )?.let { return it }
        }
        val api = "https://www.zhihu.com/api/v4/questions/${source.contentId}" +
            "?include=title,answer_count"
        val response = request(api, source.canonicalUrl, cookieHeader)
        if (response.statusCode in 200..299 && response.body.isNotBlank()) {
            return JSONObject(response.body)
        }
        val pageResponse = request(
            source.canonicalUrl,
            SourcePlatform.ZHIHU.referer,
            cookieHeader,
            html = true,
        )
        if (pageResponse.statusCode !in 200..299) throw statusError(pageResponse.statusCode)
        ZhihuPageStateExtractor.findEntity(
            pageResponse.body,
            "questions",
            source.contentId,
        )?.let { return it }
        throw statusError(response.statusCode)
    }

    private fun fetchLensVideo(
        videoId: String,
        referer: String,
        cookieHeader: String,
    ): JSONObject? {
        val response = request(
            "https://lens.zhihu.com/api/v4/videos/$videoId",
            referer,
            cookieHeader,
        )
        return response.takeIf { it.statusCode in 200..299 && it.body.isNotBlank() }
            ?.let { runCatching { JSONObject(it.body) }.getOrNull() }
    }

    private fun request(
        url: String,
        referer: String,
        cookieHeader: String,
        html: Boolean = false,
    ): ParserHttpResponse = httpClient.get(
        url,
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Accept" to if (html) {
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            } else {
                "application/json, text/plain, */*"
            },
            "Accept-Language" to "zh-CN,zh;q=0.9",
        ),
        cookieHeader = cookieHeader,
        timeoutSeconds = 25,
    )

    private fun statusError(status: Int): PlatformParseException = when (status) {
        401, 403, 429 -> PlatformParseException("AUTH_OR_RISK", "知乎认证或风控拒绝了本次请求", status)
        404, 410 -> PlatformParseException("CONTENT_UNAVAILABLE", "知乎内容不存在或已被删除", status)
        else -> PlatformParseException("HTTP_ERROR", "知乎请求失败（HTTP $status）", status)
    }

    private fun hydrateSizes(result: ParseResult): ParseResult {
        fun hydrate(variants: List<MediaVariant>): List<MediaVariant> = MediaSizeHydrator.hydrate(
            variants = variants,
            probe = { urls ->
                urls.take(2).firstNotNullOfOrNull { url ->
                    runCatching {
                        httpClient.probeContentLength(
                            url,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to result.referer,
                            ),
                        )
                    }.getOrDefault(0L).takeIf { it > 0L }
                } ?: 0L
            },
        )
        return if (result.kind == MediaKind.DOCUMENT) {
            result.copy(
                document = result.document?.let { document ->
                    document.copy(
                        assets = document.assets.map { asset ->
                            if (asset.kind == DocumentAssetKind.VIDEO) {
                                asset.copy(variants = hydrate(asset.variants))
                            } else {
                                asset
                            }
                        },
                    )
                },
            )
        } else {
            result.copy(variants = hydrate(result.variants))
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        private val RESTRICTED = Regex(
            "登录后(?:查看|阅读全文)|仅限盐选会员|付费后(?:查看|阅读)|" +
                "私密内容|无权查看|内容不可见|该内容暂不可查看",
        )
    }
}
