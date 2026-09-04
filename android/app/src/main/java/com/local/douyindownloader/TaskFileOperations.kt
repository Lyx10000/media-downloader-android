package com.local.douyindownloader

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class ManagedTransferMode(val label: String) {
    COPY("复制"),
    MOVE("移动"),
}

internal data class ManagedFileItem(
    val output: TaskOutput,
    val uri: Uri,
    val available: Boolean,
)

internal data class ManagedFileOperationResult(
    val successCount: Int,
    val failureCount: Int,
    val message: String,
)

internal fun renamedDisplayName(currentName: String, requestedBase: String): String? {
    val base = requestedBase.trim()
    if (base.isBlank() || base in setOf(".", "..") ||
        base.any { it == '/' || it == '\\' || it == '\u0000' }
    ) {
        return null
    }
    val extension = currentName.substringAfterLast('.', "")
        .takeIf { currentName.substringBeforeLast('.', "").isNotBlank() }
        .orEmpty()
    return if (extension.isBlank()) base else "$base.$extension"
}

internal fun uniqueDisplayName(preferred: String, exists: (String) -> Boolean): String {
    if (!exists(preferred)) return preferred
    val extension = preferred.substringAfterLast('.', "")
        .takeIf { preferred.substringBeforeLast('.', "").isNotBlank() }
        .orEmpty()
    val base = if (extension.isBlank()) preferred else preferred.substringBeforeLast('.')
    var index = 1
    while (true) {
        val candidate = if (extension.isBlank()) "$base ($index)" else "$base ($index).$extension"
        if (!exists(candidate)) return candidate
        index += 1
    }
}

internal fun replaceTaskOutput(
    outputs: List<TaskOutput>,
    sourceUri: String,
    replacement: TaskOutput,
): List<TaskOutput> = outputs.map { output ->
    if (output.uri == sourceUri) replacement else output
}

internal fun canShareManagedOutputs(outputs: List<TaskOutput>): Boolean =
    outputs.isNotEmpty() && canShareTogether(outputs.map { output ->
        mediaMimeType(output.displayName, output.mimeType)
    })

internal interface ManagedFileGateway {
    fun describe(output: TaskOutput): ManagedFileItem
    fun rename(output: TaskOutput, newDisplayName: String): TaskOutput
    fun copy(output: TaskOutput, destinationTree: String): TaskOutput
    fun delete(output: TaskOutput): Boolean
}

internal data class ManagedTransferAttempt(
    val success: Boolean,
    val replacement: TaskOutput? = null,
    val error: String = "",
    val rollbackFailed: Boolean = false,
)

internal fun executeManagedTransfer(
    gateway: ManagedFileGateway,
    source: TaskOutput,
    destinationTree: String,
    mode: ManagedTransferMode,
): ManagedTransferAttempt = try {
    val copied = gateway.copy(source, destinationTree)
    if (mode == ManagedTransferMode.COPY) {
        ManagedTransferAttempt(success = true)
    } else if (gateway.delete(source)) {
        ManagedTransferAttempt(success = true, replacement = copied)
    } else {
        val rolledBack = runCatching { gateway.delete(copied) }.getOrDefault(false)
        ManagedTransferAttempt(
            success = false,
            error = if (rolledBack) "无法删除源文件" else "无法删除源文件，目标可能留有副本",
            rollbackFailed = !rolledBack,
        )
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    ManagedTransferAttempt(success = false, error = error.message ?: "文件操作失败")
}

@Singleton
internal class AndroidManagedFileGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : ManagedFileGateway {
    private val resolver = context.contentResolver

    override fun describe(output: TaskOutput): ManagedFileItem {
        val uri = Uri.parse(output.uri)
        val available = isReadable(uri)
        return ManagedFileItem(
            output = if (available) queryOutput(uri, output) else output,
            uri = uri,
            available = available,
        )
    }

    override fun rename(output: TaskOutput, newDisplayName: String): TaskOutput {
        val source = Uri.parse(output.uri)
        val renamedUri = when (source.scheme) {
            ContentResolver.SCHEME_FILE -> renameFile(source, newDisplayName)
            ContentResolver.SCHEME_CONTENT -> renameContent(source, newDisplayName)
            else -> throw IOException("不支持的文件位置")
        }
        return queryOutput(
            renamedUri,
            output.copy(uri = renamedUri.toString(), displayName = newDisplayName),
        )
    }

    override fun copy(output: TaskOutput, destinationTree: String): TaskOutput {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(destinationTree))
            ?.takeIf { it.exists() && it.isDirectory && it.canWrite() }
            ?: throw IOException("目标文件夹不可写")
        val source = Uri.parse(output.uri)
        if (!isReadable(source)) throw IOException("源文件已无法读取")
        val preferredName = output.displayName.ifBlank { source.lastPathSegment ?: "download" }
        val targetName = uniqueDisplayName(preferredName) { name -> root.findFile(name) != null }
        val mimeType = mediaMimeType(preferredName, output.mimeType)
        val target = root.createFile(mimeType, targetName)
            ?: throw IOException("无法在目标文件夹创建文件")
        try {
            openInput(source).use { input ->
                resolver.openOutputStream(target.uri, "w")?.use { destination ->
                    input.copyTo(destination, DEFAULT_BUFFER_SIZE)
                } ?: throw IOException("无法写入目标文件")
            }
        } catch (error: Throwable) {
            runCatching { target.delete() }
            throw error
        }
        return queryOutput(
            target.uri,
            TaskOutput(target.uri.toString(), targetName, mimeType, output.sizeBytes),
        )
    }

    override fun delete(output: TaskOutput): Boolean {
        val uri = runCatching { Uri.parse(output.uri) }.getOrNull() ?: return false
        if (!isReadable(uri)) return true
        val deleted = when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.delete() == true
            ContentResolver.SCHEME_CONTENT -> {
                runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false) ||
                    runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() == true }
                        .getOrDefault(false)
            }
            else -> false
        }
        return deleted || !isReadable(uri)
    }

    private fun renameContent(source: Uri, newDisplayName: String): Uri {
        if (source.authority == MediaStore.AUTHORITY || source.authority == "media") {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
            }
            if (resolver.update(source, values, null, null) <= 0) {
                throw IOException("文件重命名失败")
            }
            return source
        }
        val document = DocumentFile.fromSingleUri(context, source)
            ?: throw IOException("无法访问该文件")
        if (!document.renameTo(newDisplayName)) throw IOException("文件重命名失败")
        return document.uri
    }

    private fun renameFile(source: Uri, newDisplayName: String): Uri {
        val file = source.path?.let(::File) ?: throw IOException("文件路径无效")
        val target = File(file.parentFile ?: throw IOException("无法读取上级目录"), newDisplayName)
        if (target.exists()) throw IOException("同名文件已经存在")
        if (!file.renameTo(target)) throw IOException("文件重命名失败")
        return Uri.fromFile(target)
    }

    private fun queryOutput(uri: Uri, fallback: TaskOutput): TaskOutput {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let(::File)
            return fallback.copy(
                uri = uri.toString(),
                displayName = file?.name ?: fallback.displayName,
                mimeType = mediaMimeType(file?.name.orEmpty(), fallback.mimeType),
                sizeBytes = file?.length()?.coerceAtLeast(0L) ?: fallback.sizeBytes,
            )
        }
        val metadata = runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                Pair(
                    if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L,
                )
            }
        }.getOrNull()
        val displayName = metadata?.first.orEmpty().ifBlank { fallback.displayName }
        return fallback.copy(
            uri = uri.toString(),
            displayName = displayName,
            mimeType = mediaMimeType(
                displayName,
                runCatching { resolver.getType(uri) }.getOrNull() ?: fallback.mimeType,
            ),
            sizeBytes = metadata?.second?.coerceAtLeast(0L) ?: fallback.sizeBytes,
        )
    }

    private fun isReadable(uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.isFile == true
            ContentResolver.SCHEME_CONTENT ->
                resolver.openFileDescriptor(uri, "r")?.use { it.fileDescriptor.valid() } == true
            else -> false
        }
    }.getOrDefault(false)

    private fun openInput(uri: Uri) = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> FileInputStream(
            uri.path?.let(::File) ?: throw IOException("文件路径无效"),
        )
        else -> resolver.openInputStream(uri) ?: throw IOException("无法读取源文件")
    }
}

@Singleton
internal class TaskFileOperationCoordinator @Inject constructor(
    private val store: DownloadTaskRepository,
    private val gateway: ManagedFileGateway,
    private val fileStateRefresher: TaskFileStateRefresher,
    private val logger: DiagnosticLogger,
) {
    private val operationMutex = Mutex()

    suspend fun describe(outputs: List<TaskOutput>): List<ManagedFileItem> =
        withContext(Dispatchers.IO) { outputs.map(gateway::describe) }

    suspend fun rename(
        taskId: String,
        sourceUri: String,
        requestedBase: String,
    ): ManagedFileOperationResult = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val task = store.get(taskId) ?: return@withContext failed("任务不存在")
            val source = task.outputs.firstOrNull { it.uri == sourceUri }
                ?: return@withContext failed("文件记录已经变化，请刷新后重试")
            val newName = renamedDisplayName(source.displayName, requestedBase)
                ?: return@withContext failed("文件名无效")
            if (newName == source.displayName) return@withContext failed("文件名没有变化")
            logRequested(taskId, "rename", 1)
            runCatching { gateway.rename(source, newName) }
                .fold(
                    onSuccess = { renamed ->
                        store.replaceOutputs(taskId, replaceTaskOutput(task.outputs, source.uri, renamed))
                        refresh(taskId)
                        logResult(taskId, "FILE_RENAME_RESULT", source, true)
                        logCompleted(taskId, "重命名", 1, 0)
                        ManagedFileOperationResult(1, 0, "已重命名为 ${renamed.displayName}")
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        logResult(taskId, "FILE_RENAME_RESULT", source, false, error)
                        failed(error.message ?: "重命名失败")
                    },
                )
        }
    }

    suspend fun delete(taskId: String, selectedUris: Set<String>): ManagedFileOperationResult =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val task = store.get(taskId) ?: return@withContext failed("任务不存在")
                val selected = task.outputs.filter { it.uri in selectedUris }
                if (selected.isEmpty()) return@withContext failed("没有选择可删除的文件")
                logRequested(taskId, "delete", selected.size)
                val failures = mutableListOf<String>()
                selected.forEach { output ->
                    val outcome = runCatching { gateway.delete(output) }
                    val success = outcome.getOrDefault(false)
                    if (!success) failures += outcome.exceptionOrNull()?.message
                        ?: output.displayName.ifBlank { "未命名文件" }
                    logResult(
                        taskId,
                        "FILE_DELETE_RESULT",
                        output,
                        success,
                        outcome.exceptionOrNull(),
                    )
                }
                refresh(taskId)
                completed(taskId, "删除", selected.size - failures.size, failures)
            }
        }

    suspend fun transfer(
        taskId: String,
        selectedUris: Set<String>,
        destinationTree: Uri,
        mode: ManagedTransferMode,
    ): ManagedFileOperationResult = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val task = store.get(taskId) ?: return@withContext failed("任务不存在")
            val selected = task.outputs.filter { it.uri in selectedUris }
            if (selected.isEmpty()) return@withContext failed("没有选择可${mode.label}的文件")
            logRequested(taskId, mode.name.lowercase(), selected.size)
            val replacements = mutableMapOf<String, TaskOutput>()
            val failures = mutableListOf<String>()
            selected.forEach { source ->
                val attempt = executeManagedTransfer(
                    gateway,
                    source,
                    destinationTree.toString(),
                    mode,
                )
                if (attempt.success) {
                    attempt.replacement?.let { replacements[source.uri] = it }
                } else {
                    failures += "${source.displayName.ifBlank { "未命名文件" }}：${attempt.error}"
                }
                logResult(
                    taskId,
                    if (mode == ManagedTransferMode.COPY) "FILE_COPY_RESULT"
                    else "FILE_MOVE_RESULT",
                    source,
                    attempt.success,
                    attempt.error.takeIf(String::isNotBlank)?.let(::IOException),
                )
            }
            if (replacements.isNotEmpty()) {
                val updated = task.outputs.map { replacements[it.uri] ?: it }
                store.replaceOutputs(taskId, updated)
            }
            refresh(taskId)
            completed(taskId, mode.label, selected.size - failures.size, failures)
        }
    }

    private suspend fun refresh(taskId: String) {
        store.get(taskId)?.let { fileStateRefresher.refresh(listOf(it)) }
    }

    private fun completed(
        taskId: String,
        operation: String,
        successCount: Int,
        failures: List<String>,
    ): ManagedFileOperationResult {
        val failureCount = failures.size
        val message = when {
            failureCount == 0 -> "${operation}完成：$successCount 个文件"
            successCount == 0 -> "${operation}失败：${Redactor.sanitize(failures.first())}"
            else -> "${operation}完成 $successCount 个，失败 $failureCount 个：${Redactor.sanitize(failures.first())}"
        }
        logCompleted(taskId, operation, successCount, failureCount)
        return ManagedFileOperationResult(successCount, failureCount, message)
    }

    private fun logCompleted(
        taskId: String,
        operation: String,
        successCount: Int,
        failureCount: Int,
    ) {
        runCatching {
            logger.event(taskId, "FILE_MANAGER", "FILE_OPERATION_COMPLETED", JSONObject().apply {
                put("operation", operation)
                put("success_count", successCount)
                put("failure_count", failureCount)
            })
        }
    }

    private fun failed(message: String) = ManagedFileOperationResult(
        successCount = 0,
        failureCount = 1,
        message = Redactor.sanitize(message),
    )

    private fun logRequested(taskId: String, operation: String, count: Int) {
        runCatching {
            logger.event(taskId, "FILE_MANAGER", "FILE_OPERATION_REQUESTED", JSONObject().apply {
                put("operation", operation)
                put("count", count)
            })
        }
    }

    private fun logResult(
        taskId: String,
        event: String,
        output: TaskOutput,
        success: Boolean,
        error: Throwable? = null,
    ) {
        runCatching {
            val uri = runCatching { Uri.parse(output.uri) }.getOrNull()
            logger.event(taskId, "FILE_MANAGER", event, JSONObject().apply {
                put("success", success)
                put("authority", uri?.authority.orEmpty())
                put("category", mediaCategory(mediaMimeType(output.displayName, output.mimeType)))
                put("size_bytes", output.sizeBytes)
                if (error != null) {
                    put("error", error.javaClass.name)
                    put("message", Redactor.sanitize(error.message.orEmpty()))
                }
            })
        }
    }
}
