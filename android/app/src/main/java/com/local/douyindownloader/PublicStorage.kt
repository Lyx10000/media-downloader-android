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
        val mode = effectiveMode(context, spec)
        if (mode == StorageMode.SAF) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(effectiveRoot(context, spec)))
                ?.takeIf { it.exists() && it.canWrite() }
                ?: error("自定义保存目录授权已经失效")
            root.findFile(spec.taskFolder) ?: root.createDirectory(spec.taskFolder)
                ?: error("无法创建任务目录 ${spec.taskFolder}")
        } else if (StorageInspector.hasAllFilesAccess()) {
            val directory = StorageInspector.defaultTaskDirectory(spec.taskFolder)
            if (!directory.exists() && !directory.mkdirs()) {
                error("无法创建默认下载目录")
            }
        }
    }

    fun publish(context: Context, source: File, spec: TaskSpec, displayName: String): TaskOutput {
        if (effectiveMode(context, spec) == StorageMode.SAF) {
            val uri = publishToTree(
                context,
                Uri.parse(effectiveRoot(context, spec)),
                source,
                spec.taskFolder,
                displayName,
            )
            return TaskOutput(uri.toString(), displayName, mediaMimeType(displayName))
        }
        val mime = mediaMimeType(displayName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/DouyinDownloader/${spec.taskFolder}",
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
            return TaskOutput(uri.toString(), displayName, mime)
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
    ): Uri {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("自定义保存目录授权已经失效")
        val taskFolder = root.findFile(relativeFolder)
            ?: root.createDirectory(relativeFolder)
            ?: error("无法创建任务目录 $relativeFolder")
        taskFolder.findFile(displayName)?.delete()
        val mime = mediaMimeType(displayName)
        val target = taskFolder.createFile(mime, displayName)
            ?: error("无法在自定义目录创建 $displayName")
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("无法写入 $displayName")
        return target.uri
    }

    private fun effectiveMode(context: Context, spec: TaskSpec): String =
        if (spec.storageMode != StorageMode.LEGACY) spec.storageMode
        else if (effectiveRoot(context, spec).isNotBlank()) StorageMode.SAF else StorageMode.DEFAULT

    private fun effectiveRoot(context: Context, spec: TaskSpec): String = spec.storageRoot.ifBlank {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("custom_tree_uri", "").orEmpty()
    }
}
