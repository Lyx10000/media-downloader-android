package com.local.multiplatformdownloader.core.network

import com.local.multiplatformdownloader.core.model.SourcePlatform
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Runtime transport options, never stored in task specs. No account credentials belong here. */
internal class MediaRequestProfile(
    private val headers: Map<String, String>,
    private val allowedHosts: Set<String> = emptySet(),
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    init {
        require(headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) })
    }

    internal fun validate(address: URL) {
        if (allowedHosts.isNotEmpty() && (address.protocol != "https" ||
            address.userInfo != null || address.port !in setOf(-1, 443) ||
            allowedHosts.none { address.host.equals(it, true) || address.host.endsWith(".$it", true) })) {
            throw IOException("媒体请求目标不在平台允许的 HTTPS 域名内")
        }
    }

    fun open(
        address: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        require(extraHeaders.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) })
        var target = URL(address)
        val visited = mutableSetOf<String>()
        repeat(6) {
            validate(target)
            if (!visited.add(target.toString())) throw IOException("媒体地址出现循环跳转")
            val connection = connectionFactory(target).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = allowedHosts.isEmpty()
                (headers + extraHeaders).forEach { (key, value) -> setRequestProperty(key, value) }
            }
            // Existing platforms retain their connection and automatic-redirect behavior.
            if (allowedHosts.isEmpty()) return connection
            try {
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location")
                    ?: throw IOException("媒体跳转缺少目标地址")
                target = URL(target, location)
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("媒体地址跳转次数过多")
    }

    fun probeContentLength(address: String): Long {
        val connection = open(address, 4_000, 4_000,
            mapOf("Range" to "bytes=0-0", "Accept-Encoding" to "identity"))
        return try {
            when (connection.responseCode) {
                206 -> connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                200 -> connection.contentLengthLong
                else -> -1L
            }
        } finally { connection.disconnect() }
    }

    companion object {
        fun standard(referer: String) = MediaRequestProfile(mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36",
            "Referer" to referer,
        ))

        fun forPlatform(platform: SourcePlatform, referer: String): MediaRequestProfile =
            if (platform == SourcePlatform.BILIBILI) BilibiliRequestProfile.media else standard(referer)
    }
}

/** Protocol/header values independently configured from the reference's network behavior. */
internal object BilibiliRequestProfile {
    val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
        "Referer" to "https://m.bilibili.com",
        "Origin" to "https://m.bilibili.com",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh-Hans;q=0.9",
    )
    // Anonymous A/B probes succeeded with these headers; CDN cookies are unnecessary in that test.
    val media = MediaRequestProfile(apiHeaders + mapOf("Accept" to "*/*", "Accept-Encoding" to "identity"),
        setOf("bilivideo.com", "bilivideo.cn", "hdslb.com", "acgvideo.com"))
}
