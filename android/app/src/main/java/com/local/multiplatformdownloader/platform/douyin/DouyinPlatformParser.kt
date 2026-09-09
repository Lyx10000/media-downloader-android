package com.local.multiplatformdownloader.platform.douyin

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

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())
