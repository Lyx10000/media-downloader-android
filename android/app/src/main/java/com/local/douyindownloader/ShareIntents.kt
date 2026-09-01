package com.local.douyindownloader

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal fun buildFileShareIntent(
    resolver: ContentResolver,
    outputUris: List<String>,
): Intent? {
    val uris = outputUris.mapNotNull { value ->
        runCatching { Uri.parse(value) }.getOrNull()
    }
    if (uris.isEmpty()) return null

    val intent = Intent(
        if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
    ).apply {
        type = commonShareMimeType(uris.map(resolver::getType))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(resolver, "抖音下载文件", uris.first()).apply {
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
