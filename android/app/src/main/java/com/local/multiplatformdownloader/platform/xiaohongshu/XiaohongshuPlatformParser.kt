package com.local.multiplatformdownloader.platform.xiaohongshu

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

