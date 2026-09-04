package com.local.douyindownloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class TaskDeleteResult(
    val success: Boolean,
    val message: String,
)

@Singleton
class TaskDeletionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DownloadTaskRepository,
    private val inspector: StorageInspector,
    private val logger: DiagnosticLogger,
    private val workManager: WorkManager,
) {

    suspend fun deleteTask(taskId: String, deleteFiles: Boolean): TaskDeleteResult =
        withContext(Dispatchers.IO) {
            val initialTask = store.get(taskId)
                ?: return@withContext TaskDeleteResult(true, "任务已经删除")
            val spec = store.getSpec(taskId)
            store.update(taskId, TaskStatus.DELETING, "正在删除", initialTask.progress)
            if (!stopWork(taskId)) {
                store.setDeleteFailed(taskId, initialTask.progress, "无法确认后台写入已经停止")
                return@withContext TaskDeleteResult(false, "无法停止后台任务，请稍后重试删除")
            }
            clearPrivateCache(taskId)

            if (!deleteFiles) {
                store.delete(taskId)
                logger.event(taskId, "DELETE", "RECORD_DELETED")
                return@withContext TaskDeleteResult(true, "任务记录已删除，下载文件已保留")
            }

            val task = store.get(taskId)
                ?: return@withContext TaskDeleteResult(true, "任务已经删除")
            val failures = mutableListOf<String>()
            task.outputs.forEach { output ->
                val deleted = deleteOutput(output.uri)
                logger.event(taskId, "DELETE", "OUTPUT_DELETE_RESULT", JSONObject().apply {
                    put("name", output.displayName)
                    put("deleted_or_missing", deleted)
                })
                if (!deleted) failures += output.displayName.ifBlank { "未命名文件" }
            }
            val folderResult = deleteEmptyTaskFolder(spec)
            logger.event(taskId, "DELETE", "FOLDER_DELETE_RESULT", JSONObject().apply {
                put("success", folderResult.success)
                put("retained", folderResult.retained)
                put("message", folderResult.message)
            })
            if (!folderResult.success) failures += folderResult.message

            if (failures.isEmpty()) {
                store.delete(taskId)
                logger.event(taskId, "DELETE", "TASK_AND_FILES_DELETED", JSONObject().apply {
                    put("folder", spec?.taskFolder.orEmpty())
                    put("folder_retained", folderResult.retained)
                })
                val message = if (folderResult.retained) {
                    "任务文件已删除；文件夹含其他文件，因此已保留"
                } else {
                    "任务和下载内容已删除"
                }
                TaskDeleteResult(true, message)
            } else {
                store.setDeleteFailed(taskId, task.progress, failures.joinToString("；"))
                logger.event(taskId, "DELETE", "DELETE_PARTIAL_FAILURE", JSONObject().apply {
                    put("failures", failures.joinToString(" | "))
                })
                TaskDeleteResult(false, "部分内容删除失败，可重试删除或仅删除任务记录")
            }
        }

    suspend fun deleteOutputsForRedownload(taskId: String): TaskDeleteResult =
        withContext(Dispatchers.IO) {
            val task = store.get(taskId) ?: return@withContext TaskDeleteResult(false, "任务不存在")
            val spec = store.getSpec(taskId)
            if (!stopWork(taskId)) {
                store.setDeleteFailed(taskId, task.progress, "无法确认后台写入已经停止")
                return@withContext TaskDeleteResult(false, "无法停止后台任务，请稍后重试")
            }
            clearPrivateCache(taskId)
            val failures = task.outputs.filterNot { output ->
                val deleted = deleteOutput(output.uri)
                logger.event(taskId, "REDOWNLOAD", "RESIDUAL_DELETE_RESULT", JSONObject().apply {
                    put("name", output.displayName)
                    put("deleted_or_missing", deleted)
                })
                deleted
            }
            val folderResult = deleteEmptyTaskFolder(spec)
            if (failures.isEmpty() && folderResult.success) {
                store.clearOutputs(taskId)
                TaskDeleteResult(true, "残留内容已清理")
            } else {
                store.setDeleteFailed(taskId, task.progress, "无法清理全部残留文件")
                TaskDeleteResult(false, "无法清理全部残留文件，请先重试删除")
            }
        }

    private suspend fun stopWork(taskId: String): Boolean {
        val cancellationAccepted = runCatching {
            workManager.cancelUniqueWork(DownloadScheduler.uniqueWorkName(taskId))
                .result.get(15, TimeUnit.SECONDS)
            true
        }.onFailure {
            logger.event(taskId, "DELETE", "WORK_CANCEL_FAILED", JSONObject().put("message", it.message))
        }.getOrDefault(false)
        if (!cancellationAccepted) return false

        repeat(50) {
            val active = runCatching {
                workManager.getWorkInfosForUniqueWork(DownloadScheduler.uniqueWorkName(taskId))
                    .get(5, TimeUnit.SECONDS)
                    .any { info -> !info.state.isFinished }
            }.getOrElse { return false }
            if (!active) return true
            delay(100)
        }
        return false
    }

    private fun clearPrivateCache(taskId: String) {
        File(context.cacheDir, "downloadTasks/$taskId").deleteRecursively()
    }

    private fun deleteOutput(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (!inspector.outputExists(value)) {
            return !inspector.isSafDocument(value) || inspector.hasDocumentAccess(value)
        }
        val deleted = runCatching {
            when (uri.scheme) {
                "file" -> uri.path?.let(::File)?.delete() == true
                else -> {
                    val resolverDeleted = runCatching {
                        context.contentResolver.delete(uri, null, null) > 0
                    }.getOrDefault(false)
                    resolverDeleted || runCatching {
                        DocumentFile.fromSingleUri(context, uri)?.delete() == true
                    }.getOrDefault(false)
                }
            }
        }.getOrDefault(false)
        return deleted || !inspector.outputExists(value)
    }

    private data class FolderDeleteResult(
        val success: Boolean,
        val retained: Boolean = false,
        val message: String = "",
    )

    private fun deleteEmptyTaskFolder(spec: TaskSpec?): FolderDeleteResult {
        if (spec == null || spec.taskFolder.isBlank()) return FolderDeleteResult(true, retained = true)
        return if (spec.storageMode == StorageMode.SAF) {
            deleteSafFolder(spec)
        } else {
            deleteDefaultFolder(spec.taskFolder)
        }
    }

    private fun deleteSafFolder(spec: TaskSpec): FolderDeleteResult {
        if (!inspector.isTreeAvailable(spec.storageRoot)) {
            return FolderDeleteResult(false, message = "保存目录已失效")
        }
        return runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(spec.storageRoot))
                ?: return FolderDeleteResult(false, message = "保存目录已失效")
            val folder = root.findFile(spec.taskFolder) ?: return FolderDeleteResult(true)
            pruneEmptySafDirectories(folder)
            if (folder.listFiles().isNotEmpty()) FolderDeleteResult(true, retained = true)
            else if (!folder.exists() || folder.delete()) FolderDeleteResult(true)
            else FolderDeleteResult(false, message = "任务文件夹删除失败")
        }.getOrElse { FolderDeleteResult(false, message = it.message ?: "任务文件夹删除失败") }
    }

    private fun deleteDefaultFolder(folderName: String): FolderDeleteResult {
        val folder = StorageInspector.defaultTaskDirectory(folderName)
        if (!folder.exists()) return FolderDeleteResult(true)
        if (!StorageInspector.hasAllFilesAccess()) {
            return FolderDeleteResult(false, message = "需要所有文件访问权限才能删除任务文件夹")
        }
        return if (pruneEmptyDirectoryTree(folder)) FolderDeleteResult(true)
        else FolderDeleteResult(true, retained = true)
    }

    private fun pruneEmptySafDirectories(directory: DocumentFile) {
        directory.listFiles().filter(DocumentFile::isDirectory).forEach { child ->
            pruneEmptySafDirectories(child)
            if (child.listFiles().isEmpty()) child.delete()
        }
    }
}

internal fun pruneEmptyDirectoryTree(directory: File): Boolean {
    if (!directory.exists()) return true
    val children = directory.listFiles() ?: return false
    children.filter(File::isDirectory).forEach(::pruneEmptyDirectoryTree)
    return directory.listFiles()?.takeIf(Array<File>::isEmpty)?.let { directory.delete() } == true
}
