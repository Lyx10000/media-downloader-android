package com.local.multiplatformdownloader.core.download

import java.net.URL

/** Detects the saved image extension from content because many platform CDNs omit it. */
internal fun imageExtension(header: ByteArray, fallback: String): String {
    fun startsWith(vararg bytes: Int): Boolean = bytes.indices.all { index ->
        header.getOrNull(index)?.toInt()?.and(0xff) == bytes[index]
    }
    val ascii = header.toString(Charsets.ISO_8859_1)
    return when {
        startsWith(0xff, 0xd8, 0xff) -> "jpg"
        startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
        ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a") -> "gif"
        ascii.startsWith("RIFF") && ascii.drop(8).startsWith("WEBP") -> "webp"
        startsWith(0x42, 0x4d) -> "bmp"
        ascii.drop(4).startsWith("ftypavif") || ascii.drop(4).startsWith("ftypavis") -> "avif"
        ascii.drop(4).startsWith("ftypheic") || ascii.drop(4).startsWith("ftypheix") -> "heic"
        ascii.drop(4).startsWith("ftypheif") || ascii.drop(4).startsWith("ftypmif1") -> "heif"
        else -> fallback
    }
}

/** Upgrades cleartext URLs only for the platform CDNs known to support HTTPS. */
internal fun secureDownloadUrl(address: String): String {
    val parsed = runCatching { URL(address) }.getOrNull() ?: return address
    val host = parsed.host.lowercase()
    val hasUserInfo = runCatching { parsed.toURI().userInfo != null }.getOrDefault(true)
    val trustedCdn = host == "xhscdn.com" || host.endsWith(".xhscdn.com") ||
        host == "vzuu.com" || host.endsWith(".vzuu.com") ||
        host == "zhimg.com" || host.endsWith(".zhimg.com")
    if (!parsed.protocol.equals("http", ignoreCase = true) || !trustedCdn || hasUserInfo) {
        return address
    }
    return address.replaceFirst(HTTP_SCHEME, "https://")
}

private val HTTP_SCHEME = Regex("^http://", RegexOption.IGNORE_CASE)
