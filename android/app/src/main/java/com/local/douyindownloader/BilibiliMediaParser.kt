package com.local.douyindownloader

import java.net.URI
import java.net.URLDecoder
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

internal class BilibiliParseException(val code: String, message: String) : IOException(message)

internal data class BilibiliSource(val id: String, val page: Int) {
    val isBv: Boolean get() = id.startsWith("BV")
    val url: String get() = "https://www.bilibili.com/video/$id?p=$page"
}

internal object BilibiliSourceResolver {
    private val path = Regex("/video/(BV[A-Za-z0-9]{10}|[aA][vV][1-9][0-9]{0,18})/?")
    private val videoHosts = setOf("www.bilibili.com", "bilibili.com", "m.bilibili.com")

    fun isShortLink(url: String): Boolean = safeUri(url).host.equals("b23.tv", true)

    fun resolve(url: String): BilibiliSource {
        val uri = safeUri(url)
        val match = path.matchEntire(uri.path.orEmpty())
        if (uri.host.lowercase() !in videoHosts || match == null) {
            fail("UNSUPPORTED_CONTENT", "B站目前仅支持普通投稿视频，暂不支持作者批量、番剧、直播和动态")
        }
        val parts = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }.filter { it.first == "p" }
        val page = if (parts.isEmpty()) 1 else {
            if (parts.size != 1 || !Regex("[1-9][0-9]*").matches(parts.single().second)) {
                fail("INVALID_PART", "分P参数无效，请提供一个正整数 p")
            }
            parts.single().second.toIntOrNull() ?: fail("INVALID_PART", "分P参数超出范围")
        }
        val id = match.groupValues[1].let { if (it.startsWith("av", true)) "av${it.drop(2)}" else it }
        return BilibiliSource(id, page)
    }

    fun safeUri(url: String): URI {
        val uri = try { URI(url) } catch (_: Exception) { fail("UNSUPPORTED_URL", "B站链接格式无效") }
        if (url.length > 4096 || uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.port !in setOf(-1, 80, 443)
        ) fail("UNSUPPORTED_URL", "B站链接地址不受支持")
        return uri
    }
}

internal data class BilibiliPart(
    val bvid: String,
    val cid: String,
    val page: Int,
    val title: String,
    val durationSeconds: Long,
    val metadata: JSONObject,
) {
    val canonicalUrl: String get() = "https://www.bilibili.com/video/$bvid?p=$page"
}

/** Independently implemented normalizer; only ordinary, unencrypted H.264/AAC DASH is accepted. */
internal object BilibiliMediaParser {
    fun part(detail: JSONObject, source: BilibiliSource): BilibiliPart {
        val bvid = detail.optString("bvid")
        if (!Regex("BV[A-Za-z0-9]{10}").matches(bvid) ||
            (source.isBv && bvid != source.id) ||
            (!source.isBv && detail.optString("aid") != source.id.drop(2))
        ) fail("CONTENT_MISMATCH", "B站返回的稿件与链接不一致")
        if (detail.optInt("state", 0) < 0) fail("CONTENT_UNAVAILABLE", "稿件当前不可访问")
        val rights = detail.optJSONObject("rights") ?: JSONObject()
        if (detail.optString("redirect_url").isNotBlank() ||
            listOf("pay", "ugc_pay", "arc_pay", "ugc_pay_preview", "movie").any { rights.optInt(it) > 0 } ||
            listOf("is_upower_exclusive", "is_upower_preview").any { flag(detail, it) || flag(rights, it) } ||
            detail.optJSONObject("season")?.optString("season_id").orEmpty().isNotBlank()
        ) fail("UNSUPPORTED_CONTENT", "暂不支持番剧、影视、付费或专属内容")
        if (rights.optInt("is_stein_gate") > 0) fail("UNSUPPORTED_CONTENT", "暂不支持互动分支视频")
        if (rights.has("download") && rights.optInt("download") == 0) {
            fail("PERMISSION_DENIED", "该稿件未开放下载权限")
        }
        val pages = objects(detail.optJSONArray("pages"))
        val selected = pages.singleOrNull { it.optInt("page") == source.page }
            ?: fail("INVALID_PART", "未找到指定分P；未指定 p 时仅选择第一P")
        val cid = selected.optString("cid")
        if (!Regex("[1-9][0-9]*").matches(cid)) fail("RESPONSE_SHAPE", "分P信息缺少有效 cid")
        val title = detail.optString("title").ifBlank { bvid }
        val partTitle = selected.optString("part")
        return BilibiliPart(bvid, cid, source.page,
            if (pages.size > 1) "$title · P${source.page} ${partTitle}".trim() else title,
            selected.optLong("duration").coerceAtLeast(0), detail)
    }

    fun normalize(part: BilibiliPart, play: JSONObject): ParseResult {
        if (flag(play, "is_preview") || flag(play, "is_pay_preview") || flag(play, "need_login")) {
            fail("PERMISSION_DENIED", "当前仅返回受限或预览内容，请检查官方页面的访问权限")
        }
        if (play.has("cid") && play.optString("cid") != part.cid) fail("CONTENT_MISMATCH", "播放资源分P不一致")
        if (encrypted(play)) fail("DRM_UNSUPPORTED", "不支持加密或 DRM 媒体")
        val dash = play.optJSONObject("dash") ?: run {
            if (objects(play.optJSONArray("durl")).size > 1) {
                fail("SEGMENTED_MEDIA_UNSUPPORTED", "暂不支持多段媒体，不会仅下载第一段")
            }
            fail("FORMAT_UNSUPPORTED", "暂未取得兼容的 DASH 音视频轨，请检查登录状态或稍后重试")
        }
        if (encrypted(dash)) fail("DRM_UNSUPPORTED", "不支持加密或 DRM 媒体")
        val audio = objects(dash.optJSONArray("audio"))
            .filter { compatible(it, "audio/mp4", "mp4a.40.") }
            .maxByOrNull { it.optLong("bandwidth") }
            ?: fail("AUDIO_UNAVAILABLE", "未取得兼容的 AAC 音轨，无法生成完整音视频")
        val duration = part.durationSeconds.takeIf { it > 0 } ?: dash.optLong("duration").coerceAtLeast(0)
        val variants = objects(dash.optJSONArray("video"))
            .filter { compatible(it, "video/mp4", "avc1.") }
            .map { track ->
                val bandwidth = track.optLong("bandwidth").coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                val size = track.optLong("size").coerceAtLeast(0)
                MediaVariant(
                    width = track.optInt("width").coerceAtLeast(0),
                    height = track.optInt("height").coerceAtLeast(0),
                    bitrate = bandwidth,
                    fps = frameRate(track.firstString("frameRate", "frame_rate")),
                    codec = track.optString("codecs"),
                    size = if (size > 0) size else (bandwidth.toDouble() * duration / 8).toLong(),
                    sizeSource = if (size > 0) "api" else "estimated",
                    urls = urls(track),
                )
            }.distinctBy { listOf(it.width, it.height, it.bitrate, it.codec, it.fps) }
            .sortedWith(compareByDescending<MediaVariant> { it.height }.thenByDescending { it.bitrate })
        if (variants.isEmpty()) fail("FORMAT_UNSUPPORTED", "未取得可下载的 H.264 视频档位，不支持当前编码或分片格式")
        val owner = part.metadata.optJSONObject("owner") ?: JSONObject()
        val mid = owner.optString("mid").takeIf { Regex("[1-9][0-9]*").matches(it) }.orEmpty()
        return ParseResult(
            ok = true, platform = SourcePlatform.BILIBILI,
            contentId = "${part.bvid}:${part.cid}", canonicalUrl = part.canonicalUrl,
            author = owner.optString("name"), authorStableId = mid, authorAccountId = mid,
            authorProfileUrl = if (mid.isBlank()) "" else "https://space.bilibili.com/$mid",
            authorAvatarUrl = mediaUrl(owner.optString("face")).orEmpty(),
            description = part.title, coverUrl = mediaUrl(part.metadata.optString("pic")).orEmpty(),
            bilibiliTitle = part.metadata.optString("title"),
            bilibiliParts = objects(part.metadata.optJSONArray("pages")).mapNotNull {
                BilibiliPartInfo.fromJson(JSONObject().put("cid", it.optString("cid"))
                    .put("page", it.optInt("page")).put("title", it.optString("part"))
                    .put("duration", it.optLong("duration")))
            }.distinctBy { it.cid },
            variants = variants, audioUrls = urls(audio),
            responseShape = JSONObject().put("platform", "bilibili").put("format", "dash")
                .put("video_tracks", variants.size).put("audio_tracks", 1).toString(),
            message = "当前档位来自 P${part.page}；其他分P下载前按所选档位匹配，实际可用档位可能不同（H.264/AAC）",
        )
    }

    private fun compatible(track: JSONObject, mime: String, codec: String): Boolean {
        val segment = track.optJSONObject("SegmentBase") ?: track.optJSONObject("segment_base")
        return !encrypted(track) && track.firstString("mimeType", "mime_type") == mime &&
            track.optString("codecs").startsWith(codec, true) && urls(track).isNotEmpty() &&
            segment != null && segment.firstString("Initialization", "initialization").isNotBlank() &&
            segment.firstString("indexRange", "index_range").isNotBlank()
    }

    private fun urls(track: JSONObject): List<String> = buildList {
        add(track.firstString("baseUrl", "base_url"))
        for (key in listOf("backupUrl", "backup_url")) {
            val values = track.optJSONArray(key) ?: continue
            for (index in 0 until values.length()) add(values.optString(index))
        }
    }.mapNotNull(::mediaUrl).distinct()

    internal fun mediaUrl(value: String): String? {
        val address = if (value.startsWith("//")) "https:$value" else value
        val uri = runCatching { URI(address) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.rawUserInfo != null ||
            listOf("bilivideo.com", "bilivideo.cn", "hdslb.com", "acgvideo.com").none {
                host == it || host.endsWith(".$it")
            }) return null
        return if (uri.scheme == "http") address.replaceFirst("http://", "https://") else address
    }

    private fun frameRate(value: String): Int {
        val parts = value.split('/')
        val numerator = parts.firstOrNull()?.toDoubleOrNull() ?: return 0
        val divisor = if (parts.size == 2) parts[1].toDoubleOrNull() ?: return 0 else 1.0
        return if (divisor > 0) (numerator / divisor).toInt().coerceIn(0, 240) else 0
    }

    private fun encrypted(value: JSONObject): Boolean =
        listOf("is_drm", "drm_tech_type", "drm_type", "drmType", "encryption_type").any { flag(value, it) } ||
            (value.has("drm") && !value.isNull("drm") && value.opt("drm") != false && value.opt("drm") != 0)

    private fun flag(value: JSONObject, key: String): Boolean = value.optBoolean(key, false) || value.optInt(key, 0) > 0
    private fun objects(value: JSONArray?): List<JSONObject> =
        if (value == null) emptyList() else (0 until value.length()).mapNotNull(value::optJSONObject)
}

@Singleton
internal class BilibiliPlatformParser @Inject constructor(private val http: ParserHttpClient) : PlatformParser {
    override val platform = SourcePlatform.BILIBILI

    override fun parse(shareText: String, cookieHeader: String, pageSnapshot: WebPageSnapshot?): ParseResult {
        var stage = "bilibili_link"
        return try {
            val input = extractSupportedSource(shareText)?.takeIf { it.platform == platform }?.url
                ?: fail("UNSUPPORTED_URL", "未找到B站普通视频链接")
            val source = BilibiliSourceResolver.resolve(resolveShortLink(input))
            stage = "bilibili_metadata"
            val query = if (source.isBv) "bvid=${source.id}" else "aid=${source.id.drop(2)}"
            val part = BilibiliMediaParser.part(requestData("$API/x/web-interface/view?$query", cookieHeader), source)
            stage = "bilibili_playurl"
            val play = requestData("$API/x/player/playurl?bvid=${part.bvid}&cid=${part.cid}&qn=80&fnval=16&fnver=0", cookieHeader)
            BilibiliMediaParser.normalize(part, play).copy(parserAttempts = listOf(ParserAttempt(stage, true, 200)))
        } catch (error: BilibiliParseException) {
            parseFailure(platform, error.code, error.message.orEmpty()).copy(
                parserAttempts = listOf(ParserAttempt(stage, false, errorCode = error.code)))
        } catch (_: IOException) {
            parseFailure(platform, "NETWORK", "B站网络请求失败，请稍后重试")
        } catch (_: Exception) {
            parseFailure(platform, "RESPONSE_SHAPE", "B站返回的数据结构暂不支持")
        }
    }

    private fun resolveShortLink(input: String): String {
        if (!BilibiliSourceResolver.isShortLink(input)) return input
        var url = input.replaceFirst("http://", "https://")
        val visited = mutableSetOf<String>()
        repeat(5) {
            if (!visited.add(url)) fail("URL_RESOLVE_FAILED", "B站短链出现循环跳转")
            if (!BilibiliSourceResolver.isShortLink(url)) return BilibiliSourceResolver.resolve(url).url
            val response = http.getWithoutRedirects(url, HEADERS)
            if (response.statusCode !in setOf(301, 302, 303, 307, 308)) {
                checkStatus(response.statusCode)
                fail("URL_RESOLVE_FAILED", "短链未返回稿件地址，请粘贴 BV/av 完整链接")
            }
            val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
                ?: fail("URL_RESOLVE_FAILED", "短链跳转地址缺失")
            url = BilibiliSourceResolver.safeUri(url).resolve(location).toString().replaceFirst("http://", "https://")
            // Validate before the next hop. No cookies are sent during short-link resolution.
            if (!BilibiliSourceResolver.isShortLink(url)) return BilibiliSourceResolver.resolve(url).url
        }
        fail("URL_RESOLVE_FAILED", "B站短链跳转次数过多，请粘贴完整视频链接")
    }

    internal fun credentialState(cookieHeader: String): PlatformCredentialState {
        if (detectPlatformCredential(platform, cookieHeader) != PlatformCredentialState.DETECTED) {
            return PlatformCredentialState.NOT_DETECTED
        }
        return try {
            val data = requestData("$API/x/web-interface/nav", cookieHeader)
            if (data.optBoolean("isLogin")) PlatformCredentialState.DETECTED else PlatformCredentialState.EXPIRED
        } catch (error: BilibiliParseException) {
            when (error.code) {
                "LOGIN_REQUIRED" -> PlatformCredentialState.EXPIRED
                "BILIBILI_RISK" -> PlatformCredentialState.CHALLENGE_REQUIRED
                else -> PlatformCredentialState.UNVERIFIED
            }
        } catch (_: Exception) { PlatformCredentialState.UNVERIFIED }
    }

    private fun requestData(url: String, cookie: String): JSONObject {
        val response = http.getWithoutRedirects(url, HEADERS, cookie)
        checkStatus(response.statusCode)
        val root = try { JSONObject(response.body) } catch (_: Exception) {
            fail("RESPONSE_SHAPE", "B站未返回有效数据，请检查官方页面是否需要验证")
        }
        when (root.optInt("code", Int.MIN_VALUE)) {
            0 -> return root.optJSONObject("data") ?: fail("RESPONSE_SHAPE", "B站响应缺少数据")
            -101 -> fail("LOGIN_REQUIRED", "请在首页打开B站登录环境，登录后重新解析")
            -352, -412, -509, -799 -> fail("BILIBILI_RISK", "B站要求安全验证或限制请求，请稍后在官方页面处理")
            -10403 -> fail("PERMISSION_DENIED", "当前账号或地区无权访问该内容")
            -403 -> fail("PERMISSION_DENIED", "B站拒绝访问，请检查官方页面的权限要求")
            -404, 62002, 62004 -> fail("CONTENT_UNAVAILABLE", "该稿件当前不可访问")
            else -> fail("BILIBILI_RESPONSE", "B站未返回可用资源，请检查访问权限或稍后重试")
        }
    }

    private fun checkStatus(status: Int) {
        when (status) {
            200 -> Unit
            401 -> fail("LOGIN_REQUIRED", "请在首页打开B站登录环境后重试")
            403 -> fail("PERMISSION_DENIED", "B站拒绝访问，请检查官方页面")
            412, 429 -> fail("BILIBILI_RISK", "B站暂时限制请求，请稍后重试")
            else -> fail("HTTP_ERROR", "B站请求失败（HTTP $status）")
        }
    }

    companion object {
        private const val API = "https://api.bilibili.com"
        private val HEADERS = BilibiliRequestProfile.apiHeaders
    }
}

private fun fail(code: String, message: String): Nothing = throw BilibiliParseException(code, message)
