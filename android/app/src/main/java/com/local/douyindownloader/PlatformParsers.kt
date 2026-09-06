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
        val resolution = fetchDetail(resolved, cookieHeader)
        val detail = resolution.detail ?: return parseFailure(
            platform,
            resolution.errorCode,
            resolution.message,
        ).copy(parserAttempts = resolution.attempts)
        hydrateVariantSizes(DouyinMediaNormalizer.normalize(detail, resolved.id, resolved.kind))
            .copy(parserAttempts = resolution.attempts)
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

    private fun fetchDetail(
        item: ResolvedDouyinItem,
        cookieHeader: String,
    ): DouyinResolution {
        val itemId = item.id
        val attempts = ArrayList<ParserAttempt>()
        var sawAuthOrRisk = false
        var sawUnavailable = false
        var sawNetwork = false

        fun attempt(
            name: String,
            url: String,
            headers: Map<String, String>,
            cookie: String = cookieHeader,
        ): JSONObject? {
            val response = try {
                httpClient.get(
                    url,
                    headers = headers,
                    cookieHeader = cookie,
                    timeoutSeconds = if (name == "signed_detail") 25 else 12,
                )
            } catch (_: IOException) {
                sawNetwork = true
                attempts += ParserAttempt(name, selected = false, errorCode = "NETWORK")
                return null
            } catch (_: Throwable) {
                attempts += ParserAttempt(name, selected = false, errorCode = "PARSE_FAILED")
                return null
            }
            val statusError = when (response.statusCode) {
                401, 403, 429, 461 -> "AUTH_OR_RISK"
                404, 410 -> "CONTENT_UNAVAILABLE"
                in 200..299 -> ""
                else -> "HTTP_ERROR"
            }
            if (statusError.isNotEmpty()) {
                sawAuthOrRisk = sawAuthOrRisk || statusError == "AUTH_OR_RISK"
                sawUnavailable = sawUnavailable || statusError == "CONTENT_UNAVAILABLE"
                attempts += ParserAttempt(name, selected = false, response.statusCode, statusError)
                return null
            }
            val detail = response.body.takeIf(String::isNotBlank)
                ?.let { DouyinFallbackExtractor.findExactDetailFromBody(it, itemId) }
            attempts += ParserAttempt(
                strategy = name,
                selected = detail != null,
                statusCode = response.statusCode,
                errorCode = if (detail == null) "DETAIL_EMPTY" else "",
            )
            return detail
        }

        val signedDetail = signedDetailUrl(itemId)
        val desktopHeaders = desktopHeaders("https://www.douyin.com/${if (item.kind == MediaKind.IMAGE) "note" else "video"}/$itemId")
        val strategies = listOf(
            Triple("signed_detail", signedDetail, desktopHeaders),
            Triple(
                "item_info",
                "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$itemId",
                desktopHeaders("https://www.iesdouyin.com/"),
            ),
            Triple(
                "mobile_feed",
                "https://aweme.snssdk.com/aweme/v1/feed/?type=7&aweme_id=$itemId&iid=0&device_id=0&version_code=270000&version_name=27.0.0",
                mapOf(
                    "User-Agent" to MOBILE_USER_AGENT,
                    "Referer" to "https://www.iesdouyin.com/",
                    "Accept" to "application/json, text/plain, */*",
                ),
            ),
            Triple(
                "share_video",
                "https://www.iesdouyin.com/share/video/$itemId",
                htmlHeaders("https://www.iesdouyin.com/"),
            ),
            Triple(
                "share_note",
                "https://www.iesdouyin.com/share/note/$itemId",
                htmlHeaders("https://www.iesdouyin.com/"),
            ),
            Triple(
                "work_page",
                "https://www.douyin.com/${if (item.kind == MediaKind.IMAGE) "note" else "video"}/$itemId",
                htmlHeaders("https://www.douyin.com/"),
            ),
        )
        strategies.forEach { (name, url, headers) ->
            attempt(name, url, headers)?.let { detail ->
                return DouyinResolution(detail, attempts)
            }
        }
        val code = when {
            sawAuthOrRisk -> "AUTH_OR_RISK"
            sawUnavailable -> "CONTENT_UNAVAILABLE"
            sawNetwork && attempts.all { it.errorCode == "NETWORK" } -> "NETWORK"
            else -> "DETAIL_EMPTY"
        }
        val message = when (code) {
            "AUTH_OR_RISK" -> "抖音接口或页面受到登录/风控限制，请返回首页点击抖音登录状态"
            "CONTENT_UNAVAILABLE" -> "抖音作品不存在、已删除或暂不可访问"
            "NETWORK" -> "抖音解析入口均无法连接"
            else -> "抖音多个解析入口均未返回目标作品信息"
        }
        return DouyinResolution(null, attempts, code, message)
    }

    private fun signedDetailUrl(itemId: String): String {
        val params = requestParameters(itemId)
        val paramsText = params.entries.joinToString("&") { (name, value) ->
            "${urlEncode(name)}=${urlEncode(value)}"
        }
        val signature = ABogusSigner().sign(paramsText, USER_AGENT)
        return "$DETAIL_ENDPOINT$paramsText&a_bogus=${urlEncode(signature)}"
    }

    private fun desktopHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "sec-ch-ua" to "\"Chromium\";v=\"130\", \"Microsoft Edge\";v=\"130\", \"Not?A_Brand\";v=\"99\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
    )

    private fun htmlHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9",
    )

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

    private data class DouyinResolution(
        val detail: JSONObject?,
        val attempts: List<ParserAttempt>,
        val errorCode: String = "",
        val message: String = "",
    )

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
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
    ): ParseResult {
        var resolvedCanonicalUrl = ""
        var resolvedNoteId = ""
        return try {
            val sourceUrl = XiaohongshuMediaParser.extractShareUrl(shareText)
            var response: ParserHttpResponse? = null
            var canonicalUrl = pageSnapshot?.finalUrl.orEmpty()
            var state = pageSnapshot?.takeIf { snapshot ->
                SourcePlatform.XIAOHONGSHU.matchesHost(
                    runCatching { java.net.URI(snapshot.finalUrl).host.orEmpty() }.getOrDefault(""),
                )
            }?.initialData?.let(XiaohongshuMediaParser::parseStatePayload)
            if (state == null) {
                response = requestPage(sourceUrl, cookieHeader)
                canonicalUrl = response.finalUrl
                XiaohongshuMediaParser.redirectTarget(canonicalUrl).takeIf(String::isNotBlank)?.let { target ->
                    response = requestPage(target, cookieHeader)
                    canonicalUrl = response.finalUrl
                }
            }
            resolvedCanonicalUrl = canonicalUrl
            val noteId = XiaohongshuMediaParser.noteIdFromUrl(canonicalUrl)
                .ifBlank { XiaohongshuMediaParser.noteIdFromUrl(sourceUrl) }
            resolvedNoteId = noteId
            if (noteId.isBlank()) {
                val finalLocation = runCatching {
                    java.net.URI(canonicalUrl).let { uri ->
                        uri.host.orEmpty() + uri.path.orEmpty()
                    }
                }.getOrDefault("").ifBlank { "未知页面" }
                throw PlatformParseException(
                    "URL_RESOLVE_FAILED",
                    "短链接已打开，但没有识别到小红书笔记 ID（最终落点：$finalLocation）",
                )
            }
            val pageBody = response?.body.orEmpty()
            val unavailable = UNAVAILABLE.find(pageBody)?.value
            if (unavailable != null) {
                throw PlatformParseException("CONTENT_UNAVAILABLE", "小红书笔记$unavailable")
            }
            if ("/login" in canonicalUrl || "登录后查看" in pageBody) {
                throw PlatformParseException("LOGIN_REQUIRED", "请返回首页点击小红书登录状态")
            }
            state = state ?: XiaohongshuMediaParser.extractInitialState(pageBody)
                ?: throw PlatformParseException("DETAIL_EMPTY", "页面没有返回小红书笔记状态")
            val match = XiaohongshuMediaParser.findTargetNoteWithStrategy(state, noteId)
                ?: throw PlatformParseException("DETAIL_EMPTY", "页面状态中没有匹配目标笔记")
            val note = match.note
            val normalized = XiaohongshuMediaParser.normalizeNote(note, noteId, canonicalUrl)
            val enriched = if (normalized.authorAccountId.isNotBlank()) normalized else {
                normalized.copy(
                    authorAccountId = fetchPublicAccountId(note, canonicalUrl, cookieHeader),
                )
            }
            enriched.copy(
                variants = MediaSizeHydrator.hydrate(
                    variants = enriched.variants,
                    probe = { urls -> probeVariantSize(urls) },
                ),
                parserAttempts = listOf(
                    ParserAttempt(
                        strategy = if (pageSnapshot != null) "webview_snapshot/${match.strategy}" else match.strategy,
                        selected = true,
                        statusCode = response?.statusCode ?: 200,
                    ),
                ),
            )
        } catch (error: PlatformParseException) {
            parseFailure(platform, error.code, error.message.orEmpty()).copy(
                canonicalUrl = resolvedCanonicalUrl,
                contentId = resolvedNoteId,
            )
        } catch (error: ParserHttpStatusException) {
            parseFailure(
                platform,
                if (error.statusCode in listOf(401, 403, 429)) "AUTH_OR_RISK" else "HTTP_ERROR",
                "小红书详情请求失败（HTTP ${error.statusCode}）",
            ).copy(
                canonicalUrl = resolvedCanonicalUrl,
                contentId = resolvedNoteId,
                parserAttempts = listOf(
                    ParserAttempt(
                        strategy = "page_request",
                        selected = false,
                        statusCode = error.statusCode,
                        errorCode = if (error.statusCode in listOf(401, 403, 429)) {
                            "AUTH_OR_RISK"
                        } else "HTTP_ERROR",
                    ),
                ),
            )
        } catch (error: IOException) {
            parseFailure(platform, "NETWORK", "网络请求失败：${error.javaClass.simpleName}")
        } catch (error: Exception) {
            parseFailure(platform, "PARSE_FAILED", error.message ?: error.javaClass.simpleName)
        }
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

    private fun fetchPublicAccountId(
        note: JSONObject,
        canonicalUrl: String,
        cookieHeader: String,
    ): String {
        val profileUrl = XiaohongshuMediaParser.authorProfileUrl(note, canonicalUrl)
        if (profileUrl.isBlank()) return ""
        if ("xsec_token=" !in profileUrl && cookieHeader.isBlank()) return ""
        return runCatching {
            val response = httpClient.get(
                profileUrl,
                headers = mapOf(
                    "User-Agent" to XiaohongshuMediaParser.USER_AGENT,
                    "Referer" to canonicalUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                ),
                cookieHeader = cookieHeader,
                timeoutSeconds = 8,
            )
            if (response.statusCode !in 200..299) return@runCatching ""
            XiaohongshuMediaParser.profilePublicAccountId(response.body)
        }.getOrDefault("")
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
