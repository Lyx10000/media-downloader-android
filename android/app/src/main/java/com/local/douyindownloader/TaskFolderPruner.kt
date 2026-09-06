package com.local.douyindownloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal data class TaskFolderPruneResult(
    val success: Boolean,
    val retained: Boolean = false,
    val message: String = "",
)

@Singleton
internal class TaskFolderPruner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inspector: StorageInspector,
) {
    fun pruneIfEmpty(spec: TaskSpec): TaskFolderPruneResult {
        if (spec.taskFolder.isBlank()) return TaskFolderPruneResult(true, retained = true)
        return if (spec.storageMode == StorageMode.SAF) pruneSaf(spec) else pruneDefault(spec.taskFolder)
    }

    private fun pruneSaf(spec: TaskSpec): TaskFolderPruneResult {
        if (!inspector.isTreeAvailable(spec.storageRoot)) {
            return TaskFolderPruneResult(false, message = "保存目录已失效")
        }
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(spec.storageRoot))
                ?: return TaskFolderPruneResult(false, message = "保存目录已失效")
            val folder = root.findRelativeDirectory(spec.taskFolder, create = false)
                ?: return TaskFolderPruneResult(true)
            pruneEmptySafDirectories(folder)
            if (folder.listFiles().isNotEmpty()) TaskFolderPruneResult(true, retained = true)
            else if (!folder.exists() || folder.delete()) TaskFolderPruneResult(true)
            else TaskFolderPruneResult(false, message = "任务文件夹删除失败")
        }.getOrElse { TaskFolderPruneResult(false, message = it.message ?: "任务文件夹删除失败") }
    }

    fun pruneCreatorParents(spec: TaskSpec) {
        val authorPath = spec.taskFolder.substringBeforeLast('/', "")
        if (authorPath.isBlank()) return
        if (spec.storageMode == StorageMode.SAF) {
            if (!inspector.isTreeAvailable(spec.storageRoot)) return
            val root = DocumentFile.fromTreeUri(context, Uri.parse(spec.storageRoot)) ?: return
            val author = root.findRelativeDirectory(authorPath, create = false) ?: return
            if (author.listFiles().isEmpty()) author.delete()
            val platformPath = authorPath.substringBeforeLast('/', "")
            val platform = root.findRelativeDirectory(platformPath, create = false)
            if (platform != null && platform.listFiles().isEmpty()) platform.delete()
        } else if (StorageInspector.hasAllFilesAccess()) {
            val work = StorageInspector.defaultTaskDirectory(spec.taskFolder)
            val author = work.parentFile
            if (author != null && author.listFiles()?.isEmpty() == true) author.delete()
            val platform = author?.parentFile
            if (platform != null && platform.listFiles()?.isEmpty() == true) platform.delete()
        }
    }

    private fun pruneDefault(folderName: String): TaskFolderPruneResult {
        val folder = StorageInspector.defaultTaskDirectory(folderName)
        if (!folder.exists()) return TaskFolderPruneResult(true)
        if (!StorageInspector.hasAllFilesAccess()) {
            return TaskFolderPruneResult(false, message = "需要所有文件访问权限才能删除任务文件夹")
        }
        return if (pruneEmptyDirectoryTree(folder)) TaskFolderPruneResult(true)
        else TaskFolderPruneResult(true, retained = true)
    }

    private fun pruneEmptySafDirectories(directory: DocumentFile) {
        directory.listFiles().filter(DocumentFile::isDirectory).forEach { child ->
            pruneEmptySafDirectories(child)
            if (child.listFiles().isEmpty()) child.delete()
        }
    }
}
