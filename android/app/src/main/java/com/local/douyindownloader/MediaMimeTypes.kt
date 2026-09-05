package com.local.douyindownloader

internal fun mediaMimeType(
    displayName: String,
    providerType: String? = null,
): String {
    val extensionType = when (displayName.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "avif" -> "image/avif"
        "md", "markdown" -> "text/markdown"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        else -> null
    }
    return extensionType
        ?: providerType?.takeUnless { it.isBlank() || it == "application/octet-stream" }
        ?: "application/octet-stream"
}

internal fun mediaCategory(mimeType: String): String = when {
    mimeType.startsWith("image/") -> "image"
    mimeType.startsWith("video/") -> "video"
    mimeType.startsWith("audio/") -> "audio"
    else -> "file"
}

internal fun canShareTogether(mimeTypes: List<String>): Boolean =
    mimeTypes.map(::mediaCategory).distinct().size <= 1
