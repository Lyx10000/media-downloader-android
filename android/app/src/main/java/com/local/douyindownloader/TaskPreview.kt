package com.local.douyindownloader

import android.content.ContentResolver
import android.net.Uri
import java.io.File

internal enum class TaskPreviewKind {
    IMAGE,
    VIDEO,
    AUDIO,
}

internal data class TaskPreviewMedia(
    val output: TaskOutput,
    val uri: Uri,
    val mimeType: String,
    val kind: TaskPreviewKind,
    val matchingOutputCount: Int,
)

internal fun selectTaskPreview(
    outputs: List<TaskOutput>,
    mimeTypeFor: (TaskOutput) -> String = { output ->
        mediaMimeType(output.displayName, output.mimeType)
    },
    isReadable: (TaskOutput) -> Boolean = { true },
): Pair<TaskOutput, TaskPreviewKind>? {
    val readable = outputs.filter(isReadable)
    TaskPreviewKind.entries.forEach { desiredKind ->
        readable.firstOrNull { output -> previewKind(mimeTypeFor(output)) == desiredKind }
            ?.let { return it to desiredKind }
    }
    return null
}

internal fun resolveTaskPreview(
    resolver: ContentResolver,
    outputs: List<TaskOutput>,
): TaskPreviewMedia? {
    val mimeTypes = outputs.associateWith { output ->
        val uri = runCatching { Uri.parse(output.uri) }.getOrNull()
        val providerType = uri?.let { runCatching { resolver.getType(it) }.getOrNull() }
        mediaMimeType(output.displayName, output.mimeType.ifBlank { providerType })
    }
    val readableOutputs = outputs.filter { output ->
        isPreviewOutputReadable(resolver, output.uri)
    }.toSet()
    val selected = selectTaskPreview(
        outputs = outputs,
        mimeTypeFor = { mimeTypes.getValue(it) },
        isReadable = { it in readableOutputs },
    ) ?: return null
    val (output, kind) = selected
    val uri = runCatching { Uri.parse(output.uri) }.getOrNull() ?: return null
    return TaskPreviewMedia(
        output = output,
        uri = uri,
        mimeType = mimeTypes.getValue(output),
        kind = kind,
        matchingOutputCount = readableOutputs.count { previewKind(mimeTypes.getValue(it)) == kind },
    )
}

private fun previewKind(mimeType: String): TaskPreviewKind? = when (mediaCategory(mimeType)) {
    "image" -> TaskPreviewKind.IMAGE
    "video" -> TaskPreviewKind.VIDEO
    "audio" -> TaskPreviewKind.AUDIO
    else -> null
}

private fun isPreviewOutputReadable(resolver: ContentResolver, value: String): Boolean {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    return runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.isFile == true
            ContentResolver.SCHEME_CONTENT ->
                resolver.openFileDescriptor(uri, "r")?.use { it.fileDescriptor.valid() } == true
            else -> false
        }
    }.getOrDefault(false)
}
