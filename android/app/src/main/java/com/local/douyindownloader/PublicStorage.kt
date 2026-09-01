package com.local.douyindownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

object PublicStorage {
    fun publish(context: Context, source: File, relativeFolder: String, displayName: String): Uri {
        val customRoot = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("custom_tree_uri", null)
        if (!customRoot.isNullOrBlank()) {
            return publishToTree(context, Uri.parse(customRoot), source, relativeFolder, displayName)
        }
        val mime = mediaMimeType(displayName)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/DouyinDownloader/$relativeFolder",
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
            return uri
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
}
