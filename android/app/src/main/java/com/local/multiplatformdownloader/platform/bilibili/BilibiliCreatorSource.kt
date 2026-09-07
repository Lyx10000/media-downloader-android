package com.local.multiplatformdownloader.platform.bilibili


import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.model.extractSupportedSource
import com.local.multiplatformdownloader.core.network.BilibiliRequestProfile
import com.local.multiplatformdownloader.core.network.ParserHttpClient
import com.local.multiplatformdownloader.feature.creator.CreatorMetric
import com.local.multiplatformdownloader.feature.creator.CreatorPage
import com.local.multiplatformdownloader.feature.creator.CreatorPlatformSource
import com.local.multiplatformdownloader.feature.creator.CreatorProfile
import com.local.multiplatformdownloader.feature.creator.CreatorSourceException
import com.local.multiplatformdownloader.feature.creator.CreatorWork
import com.local.multiplatformdownloader.feature.creator.creatorKey
import com.local.multiplatformdownloader.feature.creator.creatorWorkKey
import com.local.multiplatformdownloader.feature.creator.socialMetric

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

internal fun bilibiliCreatorUid(query: String): String {
    val text = query.trim().removePrefix("UID:").removePrefix("uid:").trim()
    val uid = if (text.matches(Regex("[1-9][0-9]{0,18}"))) text else {
        val url = extractSupportedSource(text)?.url ?: text
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.host?.lowercase() !in setOf("space.bilibili.com", "m.bilibili.com") ||
            uri.scheme !in setOf("https", "http") || uri.rawUserInfo != null || uri.port !in setOf(-1, 80, 443)) "" else {
            Regex("/(?:space/)?([1-9][0-9]{0,18})(?:/.*)?").matchEntire(uri.path.orEmpty())?.groupValues?.get(1).orEmpty()
        }
    }
    if (!uid.matches(Regex("[1-9][0-9]{0,18}"))) throw CreatorSourceException("AUTHOR_QUERY_UNSUPPORTED", "B站请输入UID或作者主页链接")
    return uid
}

internal fun bilibiliIpLocation(user: JSONObject): String =
    user.optJSONObject("profile")?.optString("ip_location").orEmpty().trim()
        .ifBlank { user.optString("ip_location").trim() }
        .replace(Regex("^IP\\s*属地\\s*[:：]\\s*"), "")

/** WBI protocol: sorted escaped query + public navigation key permutation + MD5. */
internal object BilibiliWbi {
    private val permutation = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)
    fun key(img: String, sub: String): String {
        val combined = listOf(img, sub).joinToString("") { URI(it).path.substringAfterLast('/').substringBefore('.') }
        require(combined.length == 64) { "B站WBI公钥格式变化" }
        return permutation.map { combined[it] }.joinToString("")
    }
    fun query(params: Map<String, String>, key: String, seconds: Long): String {
        fun encode(value: String) = URLEncoder.encode(value.filterNot { it in "!'()*" }, "UTF-8").replace("+", "%20")
        val query = (params + ("wts" to seconds.toString())).toSortedMap().entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val digest = MessageDigest.getInstance("MD5").digest((query + key).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$query&w_rid=$digest"
    }
}

@Singleton
internal class BilibiliCreatorSource @Inject constructor(private val http: ParserHttpClient) : CreatorPlatformSource {
    override val platform = SourcePlatform.BILIBILI
    private var cachedKey = ""
    private var keyAt = 0L
    @Volatile private var blockedUntil = 0L

    override fun resolve(query: String, cookieHeader: String, pageSnapshot: WebPageSnapshot?): CreatorProfile =
        profile(resolveUid(query), cookieHeader)

    private fun resolveUid(query: String): String {
        var url = extractSupportedSource(query)?.url ?: return bilibiliCreatorUid(query)
        val visited = mutableSetOf<String>()
        repeat(5) {
            val uri = try {
                BilibiliSourceResolver.safeUri(url)
            } catch (_: BilibiliParseException) {
                throw CreatorSourceException("AUTHOR_QUERY_UNSUPPORTED", "B站主页链接地址不受支持")
            }
            if (!uri.host.equals("b23.tv", true)) return bilibiliCreatorUid(url)
            url = url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            if (!visited.add(url)) throw CreatorSourceException("URL_RESOLVE_FAILED", "B站短链出现循环跳转，请复制完整主页链接")
            if (System.nanoTime() < blockedUntil) {
                throw CreatorSourceException("AUTH_OR_RISK", "B站请求已暂停，请至少一分钟后重试；本地内容保留")
            }
            // Resolve only trusted short-link hops, without forwarding the user's cookies.
            val result = http.getWithoutRedirects(url, BilibiliRequestProfile.apiHeaders, "", 15)
            if (result.statusCode in setOf(403, 412, 429)) risk("B站拒绝了短链请求（HTTP ${result.statusCode}），请稍后再试")
            if (result.statusCode !in setOf(301, 302, 303, 307, 308)) {
                throw CreatorSourceException("URL_RESOLVE_FAILED", "B站短链未返回主页地址，请重新复制主页链接")
            }
            val location = result.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
                ?.takeIf { it.isNotBlank() }
                ?: throw CreatorSourceException("URL_RESOLVE_FAILED", "B站短链跳转地址缺失")
            url = try { URI(url).resolve(location).toString() } catch (_: IllegalArgumentException) {
                throw CreatorSourceException("URL_RESOLVE_FAILED", "B站短链跳转地址无效")
            }
            // Validate the final homepage locally; never request arbitrary redirect destinations.
            val target = runCatching { BilibiliSourceResolver.safeUri(url) }.getOrNull()
                ?: throw CreatorSourceException("AUTHOR_QUERY_UNSUPPORTED", "B站短链跳转地址不受支持")
            if (!target.host.equals("b23.tv", true)) return bilibiliCreatorUid(url)
        }
        throw CreatorSourceException("URL_RESOLVE_FAILED", "B站短链跳转次数过多，请复制完整主页链接")
    }

    private fun profile(uid: String, cookie: String): CreatorProfile {
        val user = request("/x/space/wbi/acc/info", mapOf("mid" to uid), cookie, signed = true)
        if (user.optString("mid") != uid || user.optString("name").isBlank()) {
            throw CreatorSourceException("AUTHOR_NOT_FOUND", "B站没有返回匹配的作者资料")
        }
        val stats = runCatching { request("/x/relation/stat", mapOf("vmid" to uid), cookie) }.getOrNull()
        val metrics = listOfNotNull(
            socialMetric("粉丝", stats?.opt("follower")), socialMetric("关注", stats?.opt("following")),
            socialMetric("等级", user.opt("level")),
            user.optJSONObject("official")?.optString("title")?.takeIf { it.isNotBlank() }?.let { CreatorMetric("认证", it) },
        )
        return CreatorProfile(creatorKey(platform, uid), platform, uid, uid,
            "https://space.bilibili.com/$uid", avatarUrl = BilibiliMediaParser.mediaUrl(user.optString("face")).orEmpty(),
            nickname = user.optString("name"), bio = user.optString("sign"),
            location = bilibiliIpLocation(user), metrics = metrics,
            refreshedAt = System.currentTimeMillis())
    }

    override fun fetchPage(profile: CreatorProfile, cursor: String, pageNumber: Int,
        cookieHeader: String, pageSnapshot: WebPageSnapshot?): CreatorPage {
        val page = if (cursor.isBlank()) 1 else cursor.toIntOrNull()
            ?: throw CreatorSourceException("INVALID_CURSOR", "B站分页状态无效，请刷新首页")
        if (page <= 0 || page != pageNumber) throw CreatorSourceException("INVALID_CURSOR", "B站页码不一致，请刷新首页")
        val data = request("/x/space/wbi/arc/search", mapOf("mid" to profile.stableId, "pn" to "$page", "ps" to "20", "order" to "pubdate"), cookieHeader, true)
        val items = data.optJSONObject("list")?.optJSONArray("vlist")
            ?: throw CreatorSourceException("RESPONSE_CHANGED", "B站没有返回投稿列表，保留本地缓存")
        val works = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val bv = item.optString("bvid")
            if (!bv.matches(Regex("BV[A-Za-z0-9]{10}"))) return@mapNotNull null
            if (item.has("mid") && item.optString("mid") != profile.stableId) return@mapNotNull null
            val seconds = item.optString("length").split(':').fold(0L) { total, value -> total * 60 + (value.toLongOrNull() ?: 0) }
            CreatorWork(creatorWorkKey(platform, bv), profile.key, platform, bv, "https://www.bilibili.com/video/$bv",
                MediaKind.VIDEO, org.jsoup.Jsoup.parse(item.optString("title")).text(),
                coverUrl = BilibiliMediaParser.mediaUrl(item.optString("pic")).orEmpty(),
                publishedAt = item.optLong("created") * 1000L, durationMs = seconds * 1000L, pageNumber = page)
        }.distinctBy { it.key }
        val total = data.optJSONObject("page")?.optLong("count", -1) ?: -1L
        if (total < 0 || (works.isEmpty() && total > (page - 1L) * 20)) {
            throw CreatorSourceException("RESPONSE_CHANGED", "B站投稿分页不完整，保留本地缓存")
        }
        val refreshed = if (page == 1 && System.currentTimeMillis() - profile.refreshedAt >= 30_000) runCatching { profile(profile.stableId, cookieHeader) }
            .getOrElse { profile.copy(refreshError = "作者资料暂未刷新，保留已有信息") } else profile
        val enriched = refreshed.copy(metrics = refreshed.metrics.filterNot { it.label == "投稿" } + CreatorMetric("投稿", "$total"))
        val more = page * 20L < total
        return CreatorPage(enriched, works, page, cursor, if (more) "${page + 1}" else "", more)
    }

    @Synchronized private fun signingKey(cookie: String): String {
        if (cachedKey.isNotBlank() && System.nanoTime() - keyAt < 30_000_000_000L) return cachedKey
        val root = response("$API/x/web-interface/nav", cookie)
        val img = root.optJSONObject("data")?.optJSONObject("wbi_img")
            ?: throw CreatorSourceException("RESPONSE_CHANGED", "B站未返回请求签名参数")
        cachedKey = BilibiliWbi.key(img.optString("img_url"), img.optString("sub_url"))
        keyAt = System.nanoTime()
        return cachedKey
    }

    private fun request(path: String, params: Map<String, String>, cookie: String, signed: Boolean = false): JSONObject {
        val query = if (signed) BilibiliWbi.query(params, signingKey(cookie), System.currentTimeMillis() / 1000)
            else params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val root = response("$API$path?$query", cookie)
        when (root.optInt("code", Int.MIN_VALUE)) {
            0 -> return root.optJSONObject("data") ?: throw CreatorSourceException("RESPONSE_CHANGED", "B站数据不完整")
            -101 -> throw CreatorSourceException("LOGIN_REQUIRED", "请在首页登录B站后重试")
            -352, -412, -509, -799 -> risk("B站暂时限制作者请求（${root.optInt("code")}），请稍后再试；本地内容保留")
            -404 -> throw CreatorSourceException("AUTHOR_NOT_FOUND", "作者当前不可访问，不能据此判断账号已注销")
            else -> throw CreatorSourceException("RESPONSE_CHANGED", "B站作者接口未返回可用数据（${root.optInt("code")}）")
        }
    }

    private fun response(url: String, cookie: String): JSONObject {
        if (System.nanoTime() < blockedUntil) throw CreatorSourceException("AUTH_OR_RISK", "B站请求已暂停，请至少一分钟后重试；本地内容保留")
        val result = http.getWithoutRedirects(url, BilibiliRequestProfile.apiHeaders, cookie, 15)
        if (result.statusCode in setOf(403, 412, 429)) risk("B站拒绝了作者请求（HTTP ${result.statusCode}），请稍后再试")
        if (result.statusCode != 200) throw CreatorSourceException("NETWORK", "B站作者请求失败（HTTP ${result.statusCode}）")
        return runCatching { JSONObject(result.body) }.getOrElse { throw CreatorSourceException("RESPONSE_CHANGED", "B站作者响应格式变化") }
    }
    private fun risk(message: String): Nothing {
        blockedUntil = System.nanoTime() + 60_000_000_000L
        throw CreatorSourceException("AUTH_OR_RISK", message)
    }
    companion object { private const val API = "https://api.bilibili.com" }
}
