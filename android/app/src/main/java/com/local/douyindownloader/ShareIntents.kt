package com.local.douyindownloader

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

internal data class ShareableFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
) {
    val category: String get() = mediaCategory(mimeType)
}

internal fun resolveShareableFiles(
    resolver: ContentResolver,
    outputUris: List<String>,
): List<ShareableFile> = outputUris.distinct().mapNotNull { value ->
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@mapNotNull null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@mapNotNull null
    val displayName = runCatching {
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "download" }
    ShareableFile(
        uri = uri,
        displayName = displayName,
        mimeType = mediaMimeType(displayName, runCatching { resolver.getType(uri) }.getOrNull()),
    )
}

internal fun buildFileShareIntent(
    files: List<ShareableFile>,
): Intent? {
    if (files.isEmpty() || !canShareTogether(files.map(ShareableFile::mimeType))) return null
    val uris = files.map(ShareableFile::uri)
    if (uris.isEmpty()) return null

    val intent = Intent(
        if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
    ).apply {
        type = commonShareMimeType(files.map(ShareableFile::mimeType))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(
            Intent.EXTRA_TITLE,
            if (files.size == 1) files.single().displayName else "${files.size} 个下载文件",
        )
        clipData = ClipData.newRawUri("抖音下载文件", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    return intent
}

internal fun buildFileShareChooser(target: Intent): Intent =
    Intent.createChooser(target, "分享下载文件").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = target.clipData
    }

internal fun shareCacheFileName(index: Int, displayName: String): String {
    val extension = displayName.substringAfterLast('.', "")
        .replace(Regex("[^A-Za-z0-9]"), "")
        .take(12)
    val rawBase = if (extension.isBlank()) displayName else displayName.substringBeforeLast('.')
    val base = rawBase
        .replace(Regex("[^\\p{L}\\p{N}_ -]"), "_")
        .replace(Regex("_+"), "_")
        .trim(' ', '_')
        .ifBlank { "download" }
        .take(96)
    val suffix = extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
    return "${(index + 1).toString().padStart(2, '0')}_${base}$suffix"
}

internal fun commonShareMimeType(types: List<String?>): String {
    if (types.isEmpty() || types.any { it.isNullOrBlank() }) return "*/*"
    val known = types.filterNotNull().distinct()
    if (known.size == 1) return known.single()

    val families = known.map { it.substringBefore('/', missingDelimiterValue = "") }.distinct()
    return if (families.size == 1 && families.single().isNotBlank()) {
        "${families.single()}/*"
    } else {
        "*/*"
    }
}
