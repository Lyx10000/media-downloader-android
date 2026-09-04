package com.local.douyindownloader

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
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
) {
    private val parsers = listOf(douyinParser, xiaohongshuParser, zhihuParser)
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

@Singleton
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
        if (source.type == ZhihuContentType.VIDEO) {
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
        parseFailure(platform, error.code, error.message.orEmpty())
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
        if (RESTRICTED.containsMatchIn(pageResponse.body)) {
            throw PlatformParseException("CONTENT_RESTRICTED", "当前知乎内容需要登录、付费或额外权限")
        }
        return ZhihuPageStateExtractor.findEntity(pageResponse.body, entityName, source.contentId)
            ?: throw PlatformParseException("DETAIL_EMPTY", "知乎页面中没有找到目标内容")
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
        401, 403, 429 -> PlatformParseException("AUTH_OR_RISK", "知乎认证或风控拒绝了本次请求")
        404, 410 -> PlatformParseException("CONTENT_UNAVAILABLE", "知乎内容不存在或已被删除")
        else -> PlatformParseException("HTTP_ERROR", "知乎请求失败（HTTP $status）")
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
        private val RESTRICTED = Regex("登录后查看|盐选|付费|私密内容|无权查看|内容不可见")
    }
}

@Singleton
internal class DouyinPlatformParser @Inject constructor(
    private val httpClient: ParserHttpClient,
) : PlatformParser {
    override val platform = SourcePlatform.DOUYIN

    override fun parse(
        shareText: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): ParseResult = try {
        val sourceUrl = extractSupportedSource(shareText)
            ?.takeIf { it.platform == platform }
            ?.url
            ?: throw PlatformParseException("UNSUPPORTED_URL", "没有找到抖音链接")
        val resolved = resolveItem(sourceUrl)
        val detail = fetchDetail(resolved.id, cookieHeader)
            ?: return parseFailure(platform, "DETAIL_EMPTY", "抖音详情接口没有返回作品信息，请返回首页点击抖音登录状态")
        hydrateVariantSizes(DouyinMediaNormalizer.normalize(detail, resolved.id, resolved.kind))
    } catch (error: PlatformParseException) {
        parseFailure(platform, error.code, error.message.orEmpty())
    } catch (error: ParserHttpStatusException) {
        parseFailure(
            platform,
            if (error.statusCode in listOf(401, 403)) "AUTH_OR_RISK" else "HTTP_ERROR",
            "详情请求失败（HTTP ${error.statusCode}）",
        )
    } catch (error: IOException) {
        parseFailure(platform, "NETWORK", "网络请求失败：${error.javaClass.simpleName}")
    } catch (error: Exception) {
        parseFailure(platform, "PARSE_FAILED", error.message ?: error.javaClass.simpleName)
    }

    private fun resolveItem(sourceUrl: String): ResolvedDouyinItem {
        val response = httpClient.get(
            sourceUrl,
            headers = mapOf("User-Agent" to USER_AGENT),
            timeoutSeconds = 20,
        )
        val candidates = stableDistinct(listOf(sourceUrl, response.finalUrl) + response.redirectUrls)
        candidates.forEach { candidate ->
            val match = ITEM_ID.find(candidate) ?: return@forEach
            return ResolvedDouyinItem(
                id = match.groupValues[1],
                kind = if ("/note/" in candidate) MediaKind.IMAGE else MediaKind.VIDEO,
            )
        }
        throw PlatformParseException("URL_RESOLVE_FAILED", "短链已打开，但没有识别到作品 ID")
    }

    private fun fetchDetail(itemId: String, cookieHeader: String): JSONObject? {
        val params = requestParameters(itemId)
        val paramsText = params.entries.joinToString("&") { (name, value) ->
            "${urlEncode(name)}=${urlEncode(value)}"
        }
        val signature = ABogusSigner().sign(paramsText, USER_AGENT)
        val endpoint = "$DETAIL_ENDPOINT$paramsText&a_bogus=${urlEncode(signature)}"
        val response = httpClient.get(
            endpoint,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "https://www.douyin.com/video/$itemId",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "sec-ch-ua" to "\"Chromium\";v=\"130\", \"Microsoft Edge\";v=\"130\", \"Not?A_Brand\";v=\"99\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
            ),
            cookieHeader = cookieHeader,
            timeoutSeconds = 25,
        )
        if (response.statusCode !in 200..299) throw ParserHttpStatusException(response.statusCode)
        if (response.body.isBlank()) return null
        val root = JSONObject(response.body)
        return root.optJSONObject("aweme_detail")?.takeIf { root.optInt("status_code") == 0 }
    }

    private fun hydrateVariantSizes(result: ParseResult): ParseResult = result.copy(
        variants = MediaSizeHydrator.hydrate(
            variants = result.variants,
            probe = { urls -> probeVariantSize(urls, USER_AGENT, SourcePlatform.DOUYIN.referer) },
        ),
    )

    private fun probeVariantSize(urls: List<String>, userAgent: String, referer: String): Long {
        urls.take(2).forEach { url ->
            val size = runCatching {
                httpClient.probeContentLength(
                    url,
                    headers = mapOf("User-Agent" to userAgent, "Referer" to referer),
                )
            }.getOrDefault(0L)
            if (size > 0) return size
        }
        return 0L
    }

    private fun requestParameters(itemId: String): LinkedHashMap<String, String> {
        val token = buildString(184) {
            repeat(184) { append(TOKEN_CHARS[SECURE_RANDOM.nextInt(TOKEN_CHARS.length)]) }
        }
        return linkedMapOf(
            "device_platform" to "webapp",
            "aid" to "6383",
            "channel" to "channel_pc_web",
            "pc_client_type" to "1",
            "publish_video_strategy_type" to "2",
            "pc_libra_divert" to "Windows",
            "version_code" to "290100",
            "version_name" to "29.1.0",
            "cookie_enabled" to "true",
            "screen_width" to "1920",
            "screen_height" to "1080",
            "browser_language" to "zh-CN",
            "browser_platform" to "Win32",
            "browser_name" to "Edge",
            "browser_version" to "130.0.0.0",
            "browser_online" to "true",
            "engine_name" to "Blink",
            "engine_version" to "130.0.0.0",
            "os_name" to "Windows",
            "os_version" to "10",
            "cpu_core_num" to "12",
            "device_memory" to "8",
            "platform" to "PC",
            "downlink" to "10",
            "effective_type" to "4g",
            "round_trip_time" to "100",
            "msToken" to token,
            "aweme_id" to itemId,
        )
    }

    private data class ResolvedDouyinItem(val id: String, val kind: MediaKind)

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
        private const val DETAIL_ENDPOINT = "https://www.douyin.com/aweme/v1/web/aweme/detail/?"
        private const val TOKEN_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        private val SECURE_RANDOM = SecureRandom()
        private val ITEM_ID = Regex("/(?:share/)?(?:video|note)/(\\d{15,22})")
    }
}

@Singleton
internal class XiaohongshuPlatformParser @Inject constructor(
    private val httpClient: ParserHttpClient,
) : PlatformParser {
    override val platform = SourcePlatform.XIAOHONGSHU

    override fun parse(
        shareText: String,
        cookieHeader: String,
        pageSnapshot: WebPageSnapshot?,
    ): ParseResult = try {
        val sourceUrl = XiaohongshuMediaParser.extractShareUrl(shareText)
        var response = requestPage(sourceUrl, cookieHeader)
        var canonicalUrl = response.finalUrl
        XiaohongshuMediaParser.redirectTarget(canonicalUrl).takeIf(String::isNotBlank)?.let { target ->
            response = requestPage(target, cookieHeader)
            canonicalUrl = response.finalUrl
        }
        val noteId = XiaohongshuMediaParser.noteIdFromUrl(canonicalUrl)
            .ifBlank { XiaohongshuMediaParser.noteIdFromUrl(sourceUrl) }
        if (noteId.isBlank()) {
            throw PlatformParseException(
                "URL_RESOLVE_FAILED",
                "短链接已打开，但没有识别到小红书笔记 ID，请重新复制最新分享链接",
            )
        }
        val unavailable = UNAVAILABLE.find(response.body)?.value
        if (unavailable != null) {
            throw PlatformParseException("CONTENT_UNAVAILABLE", "小红书笔记$unavailable")
        }
        if ("/login" in canonicalUrl || "登录后查看" in response.body) {
            throw PlatformParseException("LOGIN_REQUIRED", "请返回首页点击小红书登录状态")
        }
        val state = XiaohongshuMediaParser.extractInitialState(response.body)
            ?: throw PlatformParseException("DETAIL_EMPTY", "页面没有返回小红书笔记状态")
        val note = XiaohongshuMediaParser.findTargetNote(state, noteId)
            ?: throw PlatformParseException("DETAIL_EMPTY", "页面状态中没有匹配目标笔记")
        val normalized = XiaohongshuMediaParser.normalizeNote(note, noteId, canonicalUrl)
        normalized.copy(
            variants = MediaSizeHydrator.hydrate(
                variants = normalized.variants,
                probe = { urls -> probeVariantSize(urls) },
            ),
        )
    } catch (error: PlatformParseException) {
        parseFailure(platform, error.code, error.message.orEmpty())
    } catch (error: ParserHttpStatusException) {
        parseFailure(
            platform,
            if (error.statusCode in listOf(401, 403, 429)) "AUTH_OR_RISK" else "HTTP_ERROR",
            "小红书详情请求失败（HTTP ${error.statusCode}）",
        )
    } catch (error: IOException) {
        parseFailure(platform, "NETWORK", "网络请求失败：${error.javaClass.simpleName}")
    } catch (error: Exception) {
        parseFailure(platform, "PARSE_FAILED", error.message ?: error.javaClass.simpleName)
    }

    private fun requestPage(url: String, cookieHeader: String): ParserHttpResponse {
        val response = httpClient.get(
            url,
            headers = mapOf(
                "User-Agent" to XiaohongshuMediaParser.USER_AGENT,
                "Referer" to XiaohongshuMediaParser.HOME_URL,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "zh-CN,zh;q=0.9",
            ),
            cookieHeader = cookieHeader,
            timeoutSeconds = 25,
        )
        if (response.statusCode !in 200..299) throw ParserHttpStatusException(response.statusCode)
        return response
    }

    private fun probeVariantSize(urls: List<String>): Long {
        urls.take(2).forEach { url ->
            val size = runCatching {
                httpClient.probeContentLength(
                    url,
                    headers = mapOf(
                        "User-Agent" to XiaohongshuMediaParser.USER_AGENT,
                        "Referer" to XiaohongshuMediaParser.HOME_URL,
                    ),
                )
            }.getOrDefault(0L)
            if (size > 0) return size
        }
        return 0L
    }

    companion object {
        private val UNAVAILABLE = Regex("该笔记已被删除|笔记不存在|暂时无法浏览|无法浏览|无法查看|违规|私密")
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

private fun parseFailure(platform: SourcePlatform, code: String, message: String): ParseResult =
    ParseResult(ok = false, platform = platform, errorCode = code, message = message)

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())
