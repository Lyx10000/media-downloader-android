package com.local.douyindownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

object PublicStorage {
    fun ensureDestination(context: Context, spec: TaskSpec) {
        val mode = effectiveMode(spec)
        if (mode == StorageMode.SAF) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(effectiveRoot(spec)))
                ?.takeIf { it.exists() && it.canWrite() }
                ?: error("自定义保存目录授权已经失效")
            root.findRelativeDirectory(spec.taskFolder, create = true)
                ?: error("无法创建任务目录 ${spec.taskFolder}")
        } else if (StorageInspector.hasAllFilesAccess()) {
            val directory = StorageInspector.defaultTaskDirectory(spec.taskFolder)
            if (!directory.exists() && !directory.mkdirs()) {
                error("无法创建默认下载目录")
            }
        }
    }

    fun publish(
        context: Context,
        source: File,
        spec: TaskSpec,
        displayName: String,
        relativeDirectory: String = "",
    ): TaskOutput {
        val safeDirectory = validatedRelativeDirectory(relativeDirectory)
        val relativePath = listOf(safeDirectory, displayName).filter(String::isNotBlank).joinToString("/")
        if (effectiveMode(spec) == StorageMode.SAF) {
            val uri = publishToTree(
                context,
                Uri.parse(effectiveRoot(spec)),
                source,
                spec.taskFolder,
                displayName,
                safeDirectory,
            )
            return TaskOutput(
                uri.toString(),
                displayName,
                mediaMimeType(displayName),
                source.length(),
                relativePath,
            )
        }
        val mime = mediaMimeType(displayName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/DouyinDownloader/${spec.taskFolder}" +
                    safeDirectory.takeIf(String::isNotBlank)?.let { "/$it" }.orEmpty(),
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在公共下载目录创建 $displayName")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入 $displayName")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return TaskOutput(uri.toString(), displayName, mime, source.length(), relativePath)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishToTree(
        context: Context,
        treeUri: Uri,
        source: File,
        relativeFolder: String,
        displayName: String,
        relativeDirectory: String,
    ): Uri {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("自定义保存目录授权已经失效")
        val taskFolder = root.findRelativeDirectory(relativeFolder, create = true)
            ?: error("无法创建任务目录 $relativeFolder")
        val destination = relativeDirectory.split('/').filter(String::isNotBlank).fold(taskFolder) { parent, name ->
            parent.findFile(name)?.takeIf(DocumentFile::isDirectory)
                ?: parent.createDirectory(name)
                ?: error("无法创建子目录 $relativeDirectory")
        }
        destination.findFile(displayName)?.delete()
        val mime = mediaMimeType(displayName)
        val target = destination.createFile(mime, displayName)
            ?: error("无法在自定义目录创建 $displayName")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("无法写入 $displayName")
        return target.uri
    }

    private fun effectiveMode(spec: TaskSpec): StorageMode =
        if (spec.storageMode != StorageMode.LEGACY) spec.storageMode
        else if (effectiveRoot(spec).isNotBlank()) StorageMode.SAF else StorageMode.DEFAULT

    private fun effectiveRoot(spec: TaskSpec): String = spec.storageRoot

    private fun validatedRelativeDirectory(value: String): String {
        val normalized = value.trim().trim('/').replace('\\', '/')
        if (normalized.isBlank()) return ""
        require(normalized.split('/').all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && '\u0000' !in segment
        }) { "保存子目录不合法" }
        return normalized
    }
}

internal fun DocumentFile.findRelativeDirectory(path: String, create: Boolean): DocumentFile? {
    val segments = path.trim('/').split('/').filter(String::isNotBlank)
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return null
    return segments.fold(this as DocumentFile?) { parent, name ->
        parent?.findFile(name)?.takeIf(DocumentFile::isDirectory)
            ?: if (create) parent?.createDirectory(name) else null
    }
}
