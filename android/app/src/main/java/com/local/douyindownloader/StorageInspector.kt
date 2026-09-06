package com.local.douyindownloader

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun defaultStorageCapacity(): Pair<Long, Long>? = runCatching {
        val stats = StatFs(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
        )
        stats.availableBytes to stats.totalBytes
    }.getOrNull()

    fun inspect(task: TaskRecord, spec: TaskSpec?): FileState {
        if (task.fileState == FileState.DELETE_FAILED) return FileState.DELETE_FAILED
        if (spec?.storageMode == StorageMode.SAF && !isTreeAvailable(spec.storageRoot)) {
            return FileState.STORAGE_UNAVAILABLE
        }
        if (task.outputs.any { isSafDocument(it.uri) && !hasDocumentAccess(it.uri) }) {
            return FileState.STORAGE_UNAVAILABLE
        }
        if (task.outputs.isEmpty()) return FileState.UNKNOWN

        return fileStateForExistence(task.outputs.map { outputExists(it.uri) })
    }

    fun outputExists(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let(::File)?.isFile == true
                else -> context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
            }
        }.getOrDefault(false)
    }

    fun isTreeAvailable(value: String): Boolean {
        if (value.isBlank()) return false
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val hasGrant = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!hasGrant) return false
        return runCatching {
            DocumentFile.fromTreeUri(context, uri)?.let { it.exists() && it.canWrite() } == true
        }.getOrDefault(false)
    }

    fun isSafDocument(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return runCatching {
            uri.scheme == "content" && DocumentsContract.isDocumentUri(context, uri) &&
                uri.authority != "media"
        }.getOrDefault(false)
    }

    fun hasDocumentAccess(value: String): Boolean {
        val documentUri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull()
            ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            if (!permission.isReadPermission || !permission.isWritePermission) return@any false
            val rootId = runCatching {
                DocumentsContract.getTreeDocumentId(permission.uri)
            }.getOrNull() ?: return@any false
            documentId == rootId || documentId.startsWith("$rootId/")
        }
    }

    companion object {
        fun hasAllFilesAccess(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

        @Suppress("DEPRECATION")
        fun defaultTaskDirectory(folderName: String): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DouyinDownloader/$folderName",
        )
    }
}

internal fun fileStateForExistence(exists: List<Boolean>): FileState = when {
    exists.isEmpty() -> FileState.UNKNOWN
    exists.all { it } -> FileState.AVAILABLE
    exists.none { it } -> FileState.MISSING
    else -> FileState.PARTIAL
}
