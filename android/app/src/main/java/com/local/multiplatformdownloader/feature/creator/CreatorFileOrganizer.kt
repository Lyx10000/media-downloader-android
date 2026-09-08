package com.local.multiplatformdownloader.feature.creator

import com.local.multiplatformdownloader.core.compat.ProductIdentity
import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.StorageMode
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.storage.TaskFolderPruner
import com.local.multiplatformdownloader.core.storage.findRelativeDirectory

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class CreatorOrganizationResult(
    val organized: Int,
    val failed: Int,
    val message: String,
)

/** Moves completed independent works into a stable author directory before follow is finalized. */
@Singleton
internal class CreatorFileOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tasks: DownloadTaskRepository,
    private val creators: CreatorLibraryRepository,
    private val folderPruner: TaskFolderPruner,
    private val logger: DiagnosticLogger,
) {
    suspend fun organize(profile: CreatorProfile): CreatorOrganizationResult = withContext(Dispatchers.IO) {
        val candidates = tasks.listAll().filter { task ->
            task.authorKey.isBlank() && taskCreatorKey(task) == profile.key && task.outputs.isNotEmpty()
        }
        var organized = 0
        val failures = mutableListOf<String>()
        candidates.forEach { task ->
            try {
                tasks.getSpec(task.id)?.let { creators.upsertFromParse(it.result, createAuthorIfMissing = true) }
                organizeTask(profile, task)
                organized += 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                failures += "${task.title}：$detail"
                // Preserve logical author grouping even when the storage provider cannot move
                // files. A later organize attempt can finish the physical migration.
                runCatching {
                    tasks.updateAuthorKey(task.id, profile.key)
                    tasks.getSpec(task.id)?.let { spec ->
                        tasks.replaceSpec(task.id, spec.copy(authorKey = profile.key))
                    }
                }
                runCatching {
                    logger.event(task.id, "ORGANIZE", "CREATOR_FILE_ORGANIZE_FAILED", JSONObject().apply {
                        put("creator_key", profile.key)
                        put("message", detail)
                    })
                }
            }
        }
        CreatorOrganizationResult(
            organized = organized,
            failed = failures.size,
            message = when {
                candidates.isEmpty() -> "作者已收藏"
                failures.isEmpty() -> "作者已收藏，已整理 $organized 个独立作品"
                organized == 0 -> "作者已收藏，但文件整理失败：${failures.first()}"
                else -> "作者已收藏，已整理 $organized 个作品，${failures.size} 个待稍后整理"
            },
        )
    }

    private suspend fun organizeTask(profile: CreatorProfile, task: TaskRecord) {
        val spec = tasks.getSpec(task.id) ?: throw IOException("任务下载信息不存在")
        val work = CreatorWork(
            key = creatorWorkKey(task.platform, task.contentId),
            creatorKey = profile.key,
            platform = task.platform,
            contentId = task.contentId,
            canonicalUrl = spec.result.canonicalUrl,
            kind = spec.result.kind,
            title = task.title,
            publishedAt = spec.result.publishedAt,
        )
        val targetFolder = creatorWorkFolder(profile, work, task.createdAt)
        if (spec.taskFolder == targetFolder) {
            tasks.updateAuthorKey(task.id, profile.key)
            tasks.replaceSpec(task.id, spec.copy(authorKey = profile.key))
            return
        }
        val replacements = when (spec.storageMode) {
            StorageMode.SAF -> moveSafOutputs(spec.storageRoot, targetFolder, task.outputs)
            else -> moveMediaStoreOutputs(targetFolder, task.outputs)
        }
        tasks.replaceOutputs(task.id, replacements)
        tasks.replaceSpec(task.id, spec.copy(authorKey = profile.key, taskFolder = targetFolder))
        tasks.updateAuthorKey(task.id, profile.key)
        folderPruner.pruneIfEmpty(spec)
        logger.event(task.id, "ORGANIZE", "CREATOR_FILE_ORGANIZED", JSONObject().apply {
            put("creator_key", profile.key)
            put("from", spec.taskFolder)
            put("to", targetFolder)
            put("files", replacements.size)
        })
    }

    private fun moveMediaStoreOutputs(
        targetFolder: String,
        outputs: List<TaskOutput>,
    ): List<TaskOutput> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("当前 Android 版本不支持安全整理公共下载目录")
        }
        val resolver = context.contentResolver
        val moved = mutableListOf<Pair<Uri, String>>()
        try {
            outputs.forEach { output ->
                val uri = Uri.parse(output.uri)
                if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
                    uri.authority !in setOf(MediaStore.AUTHORITY, "media")
                ) {
                    throw IOException("文件不属于可整理的公共下载目录")
                }
                val oldRelativePath = queryRelativePath(resolver, uri)
                val childDirectory = output.relativePath.substringBeforeLast('/', "")
                val destination = buildString {
                    append(Environment.DIRECTORY_DOWNLOADS)
                    append('/')
                    append(ProductIdentity.DEFAULT_DOWNLOAD_DIRECTORY)
                    append('/')
                    append(targetFolder.trim('/'))
                    if (childDirectory.isNotBlank()) append('/').append(childDirectory.trim('/'))
                    append('/')
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, destination)
                }
                if (resolver.update(uri, values, null, null) <= 0) {
                    throw IOException("无法移动 ${output.displayName}")
                }
                moved += uri to oldRelativePath
            }
            return outputs
        } catch (error: Throwable) {
            moved.asReversed().forEach { (uri, oldPath) ->
                runCatching {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, oldPath) },
                        null,
                        null,
                    )
                }
            }
            throw error
        }
    }

    private fun queryRelativePath(resolver: ContentResolver, uri: Uri): String =
        resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ""
            cursor.getString(0).orEmpty()
        }.orEmpty().ifBlank { throw IOException("无法读取原文件目录") }

    private fun moveSafOutputs(
        treeValue: String,
        targetFolder: String,
        outputs: List<TaskOutput>,
    ): List<TaskOutput> {
        val tree = Uri.parse(treeValue)
        val root = DocumentFile.fromTreeUri(context, tree)
            ?.takeIf { it.exists() && it.canWrite() }
            ?: throw IOException("自定义保存目录授权已经失效")
        val copied = mutableListOf<Pair<TaskOutput, DocumentFile>>()
        try {
            outputs.forEach { output ->
                val source = Uri.parse(output.uri)
                context.contentResolver.openInputStream(source)?.use { input ->
                    val relativeDirectory = output.relativePath.substringBeforeLast('/', "")
                    val directoryPath = listOf(targetFolder, relativeDirectory)
                        .filter(String::isNotBlank)
                        .joinToString("/")
                    val targetDirectory = root.findRelativeDirectory(directoryPath, create = true)
                        ?: throw IOException("无法创建作者目录")
                    val target = targetDirectory.createFile(
                        output.mimeType.ifBlank { "application/octet-stream" },
                        output.displayName,
                    ) ?: throw IOException("无法创建 ${output.displayName}")
                    context.contentResolver.openOutputStream(target.uri, "w")?.use { destination ->
                        input.copyTo(destination)
                    } ?: throw IOException("无法写入 ${output.displayName}")
                    val targetSize = target.length()
                    if (output.sizeBytes > 0L && targetSize > 0L && targetSize != output.sizeBytes) {
                        target.delete()
                        throw IOException("${output.displayName} 复制后大小不一致")
                    }
                    copied += output to target
                } ?: throw IOException("无法读取 ${output.displayName}")
            }
        } catch (error: Throwable) {
            copied.forEach { (_, target) -> runCatching { target.delete() } }
            throw error
        }
        val replacements = copied.map { (source, target) ->
            source.copy(uri = target.uri.toString(), sizeBytes = target.length().coerceAtLeast(source.sizeBytes))
        }
        copied.forEach { (source, _) ->
            val sourceDocument = DocumentFile.fromSingleUri(context, Uri.parse(source.uri))
            if (sourceDocument?.delete() != true) {
                // Keep the valid target and update the task record. The stale source is harmless,
                // and is preferable to rolling back by deleting the only verified copy.
                logger.event("file-organizer", "ORGANIZE", "SOURCE_DELETE_RETAINED", JSONObject().apply {
                    put("name", source.displayName)
                })
            }
        }
        return replacements
    }
}
