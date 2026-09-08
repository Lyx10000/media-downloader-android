package com.local.multiplatformdownloader.core.model

import java.net.URI
import org.json.JSONObject

enum class UpdateSource { MIRROR, GITHUB }

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    VALIDATING,
    READY_TO_INSTALL,
    FAILED,
}

data class UpdateAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String = "",
)

data class UpdateRelease(
    val versionName: String,
    val title: String,
    val changelog: String,
    val pageUrl: String,
    val asset: UpdateAsset?,
)

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val release: UpdateRelease? = null,
    val source: UpdateSource? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val message: String = "尚未检查更新",
) {
    val progress: Float?
        get() = totalBytes.takeIf { it > 0L }?.let {
            (downloadedBytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

internal object UpdateReleaseParser {
    fun parse(root: JSONObject): UpdateRelease? {
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val version = root.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        if (version.isBlank()) return null
        val assets = root.optJSONArray("assets")
        val candidates = if (assets == null) emptyList() else (0 until assets.length()).mapNotNull { index ->
            val asset = assets.optJSONObject(index) ?: return@mapNotNull null
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || !isOfficialUpdateAsset(url)) return@mapNotNull null
            UpdateAsset(
                name = name,
                url = url,
                sizeBytes = asset.optLong("size").coerceAtLeast(0L),
                sha256 = asset.optString("digest")
                    .substringAfter("sha256:", "")
                    .lowercase()
                    .takeIf { it.length == 64 && it.all(Char::isHexDigit) }
                    .orEmpty(),
            )
        }
        val arm64 = candidates.filter { "arm64" in it.name.lowercase() }
        val selected = when {
            arm64.size == 1 -> arm64.single()
            candidates.size == 1 -> candidates.single()
            else -> null
        }
        return UpdateRelease(
            versionName = version,
            title = root.optString("name").ifBlank { "版本 $version" },
            changelog = root.optString("body").ifBlank { "暂无更新说明" },
            pageUrl = root.optString("html_url").ifBlank { RELEASES_PAGE },
            asset = selected,
        )
    }

    private const val RELEASES_PAGE =
        "https://github.com/Lyx10000/multi-platform-downloader-android/releases"
}

internal fun compareReleaseVersions(first: String, second: String): Int {
    fun parts(value: String): List<Int> = value.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '_')
        .take(4)
        .map { component -> component.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val left = parts(first)
    val right = parts(second)
    val length = maxOf(left.size, right.size, 3)
    repeat(length) { index ->
        val comparison = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}

internal fun shouldCheckForUpdate(
    manual: Boolean,
    todayEpochDay: Long,
    lastCheckEpochDay: Long,
): Boolean = manual || todayEpochDay != lastCheckEpochDay

internal fun isOfficialUpdateAsset(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val officialReleasePrefixes = listOf(
        "/Lyx10000/multi-platform-downloader-android/releases/download/",
        // Keep accepting assets created before the repository rename. This is a
        // compatibility identity, like the published Android application ID.
        "/Lyx10000/media-downloader-android/releases/download/",
    )
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("github.com", ignoreCase = true) &&
        officialReleasePrefixes.any(uri.path::startsWith)
}

internal fun updateDownloadUrl(originalUrl: String, source: UpdateSource): String {
    require(isOfficialUpdateAsset(originalUrl)) { "更新地址不是官方 Release 资产" }
    return when (source) {
        UpdateSource.MIRROR -> "https://gh-proxy.org/$originalUrl"
        UpdateSource.GITHUB -> originalUrl
    }
}

internal fun validateUpdateIdentity(
    expectedPackage: String,
    currentVersionCode: Long,
    currentSigners: Set<String>,
    archivePackage: String,
    archiveVersionCode: Long,
    archiveSigners: Set<String>,
) {
    require(archivePackage == expectedPackage) { "安装包包名不匹配" }
    require(archiveVersionCode > currentVersionCode) { "安装包版本不高于当前版本" }
    require(archiveSigners.isNotEmpty() && archiveSigners == currentSigners) {
        "安装包签名与当前应用不一致"
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'
