package com.local.multiplatformdownloader.feature.document

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.download.mediaMimeType
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.storage.StorageInspector
import com.local.multiplatformdownloader.feature.tasks.ManagedFileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MAX_MARKDOWN_PREVIEW_CHARS = 2_000_000

internal class TaskContentCoordinator @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val store: DownloadTaskRepository,
    private val inspector: StorageInspector,
    private val logger: DiagnosticLogger,
) {
    suspend fun loadDocumentReader(task: TaskRecord): DocumentReaderData? =
        withContext(Dispatchers.IO) {
            val document = store.getSpec(task.id)?.result?.document ?: return@withContext null
            val availableOutputs = task.outputs.filter { inspector.outputExists(it.uri) }
            val assetOutputs = resolveDocumentAssetOutputs(document, availableOutputs)
            runCatching {
                logger.event(task.id, "DOCUMENT_READER", "DOCUMENT_OPENED", JSONObject().apply {
                    put("assets", document.assets.size)
                    put("local_assets", assetOutputs.size)
                    put("missing_assets", document.assets.size - assetOutputs.size)
                })
            }
            DocumentReaderData(document, assetOutputs)
        }

    suspend fun loadMarkdownOutput(taskId: String, output: TaskOutput): String? =
        withContext(Dispatchers.IO) {
            if (!inspector.outputExists(output.uri)) return@withContext null
            val uri = runCatching { Uri.parse(output.uri) }.getOrNull() ?: return@withContext null
            val input = runCatching {
                if (uri.scheme == "file") {
                    uri.path?.let { path -> java.io.File(path).inputStream() }
                } else {
                    applicationContext.contentResolver.openInputStream(uri)
                }
            }.getOrNull() ?: return@withContext null
            runCatching {
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(8_192)
                    while (text.length < MAX_MARKDOWN_PREVIEW_CHARS) {
                        val count = reader.read(
                            buffer,
                            0,
                            minOf(buffer.size, MAX_MARKDOWN_PREVIEW_CHARS - text.length),
                        )
                        if (count < 0) break
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }
            }.onFailure { error ->
                logger.event(taskId, "DOCUMENT_READER", "MARKDOWN_READ_FAILED", JSONObject().apply {
                    put("name", output.displayName)
                    put("type", error.javaClass.name)
                    put("message", Redactor.sanitize(error.message.orEmpty()))
                })
            }.getOrNull()
        }

    fun openDocumentMedia(
        context: Context,
        taskId: String,
        output: TaskOutput,
    ): String? {
        val uri = runCatching { Uri.parse(output.uri) }.getOrNull()
        if (uri == null || !inspector.outputExists(output.uri)) return "文件已被删除"
        return openOutput(context, taskId, uri, output.displayName, output.mimeType)
    }

    fun openManagedFile(context: Context, taskId: String, item: ManagedFileItem): String? {
        if (!item.available) return "文件已被删除"
        return openOutput(
            context,
            taskId,
            item.uri,
            item.output.displayName,
            item.output.mimeType,
        )
    }

    fun recordImageLoadFailed(
        taskId: String,
        assetId: String,
        outputName: String,
        sourceScheme: String,
        error: Throwable,
    ) {
        runCatching {
            logger.event(taskId, "DOCUMENT_READER", "IMAGE_LOAD_FAILED", JSONObject().apply {
                put("asset_id", assetId)
                put("output_name", outputName)
                put("source_scheme", sourceScheme)
                put("type", error.javaClass.name)
                put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
            })
        }
    }

    private fun openOutput(
        context: Context,
        taskId: String,
        uri: Uri,
        displayName: String,
        providerType: String,
    ): String? {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mediaMimeType(displayName, providerType))
            clipData = ClipData.newRawUri("下载文件", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }
            .fold(
                onSuccess = { null },
                onFailure = { error ->
                    runCatching {
                        logger.event(taskId, "FILE_MANAGER", "FILE_OPEN_FAILED", JSONObject().apply {
                            put("authority", uri.authority.orEmpty())
                            put("error", error.javaClass.name)
                            put("message", Redactor.sanitize(error.message.orEmpty()))
                        })
                    }
                    "没有可打开该文件的应用"
                },
            )
    }
}
