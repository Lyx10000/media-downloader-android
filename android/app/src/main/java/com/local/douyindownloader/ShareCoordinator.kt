package com.local.douyindownloader

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

internal data class ShareLaunchResult(
    val launched: Boolean,
    val message: String = "",
)

@Singleton
class ShareCoordinator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val logger: DiagnosticLogger,
) {
    internal suspend fun share(
        launchContext: Context,
        taskId: String,
        files: List<ShareableFile>,
    ): ShareLaunchResult {
        log(taskId, "SHARE_PREPARE", JSONObject().apply {
            put("files", files.size)
            put("mime", commonShareMimeType(files.map(ShareableFile::mimeType)))
            put("authorities", files.mapNotNull { it.uri.authority }.distinct().joinToString(","))
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
        })
        if (files.isEmpty() || !canShareTogether(files.map(ShareableFile::mimeType))) {
            return fail(taskId, "selection", IllegalArgumentException("没有可分享文件或文件类型混合"))
        }

        val unreadable = withContext(Dispatchers.IO) {
            files.firstOrNull { file -> !isReadable(appContext.contentResolver, file) }
        }
        if (unreadable != null) {
            return fail(taskId, "validate_uri", IllegalStateException("文件已无法读取"))
        }
        log(taskId, "URI_VALIDATED", JSONObject().put("files", files.size))

        val directError = launch(launchContext, files)
        if (directError == null) {
            log(taskId, "CHOOSER_LAUNCH", JSONObject().put("fallback", false))
            return ShareLaunchResult(launched = true)
        }
        logFailure(taskId, "direct_launch", directError)

        val fallbackFiles = try {
            withContext(Dispatchers.IO) { createCacheCopies(taskId, files) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return fail(taskId, "cache_copy", error)
        }
        log(taskId, "CACHE_FALLBACK_READY", JSONObject().put("files", fallbackFiles.size))

        val fallbackError = launch(launchContext, fallbackFiles)
        return if (fallbackError == null) {
            log(taskId, "CHOOSER_LAUNCH", JSONObject().put("fallback", true))
            ShareLaunchResult(true, "已使用兼容模式打开分享")
        } else {
            fail(taskId, "fallback_launch", fallbackError)
        }
    }

    private suspend fun launch(context: Context, files: List<ShareableFile>): Throwable? =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                val target = buildFileShareIntent(files) ?: error("无法构造分享请求")
                val chooser = buildFileShareChooser(target)
                context.startActivity(chooser)
            }.exceptionOrNull()
        }

    private fun isReadable(resolver: ContentResolver, file: ShareableFile): Boolean =
        runCatching {
            resolver.openFileDescriptor(file.uri, "r")?.use { descriptor ->
                descriptor.fileDescriptor.valid()
            } == true
        }.getOrDefault(false)

    private fun createCacheCopies(
        taskId: String,
        files: List<ShareableFile>,
    ): List<ShareableFile> {
        val root = File(appContext.cacheDir, SHARE_CACHE_DIRECTORY).apply { mkdirs() }
        trimOldCache(root)
        val safeTaskId = taskId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val taskFolder = File(root, safeTaskId.ifBlank { "share" })
        taskFolder.deleteRecursively()
        check(taskFolder.mkdirs()) { "无法创建兼容分享缓存" }

        return files.mapIndexed { index, file ->
            val cacheFile = File(taskFolder, shareCacheFileName(index, file.displayName))
            val input = appContext.contentResolver.openInputStream(file.uri)
                ?: error("无法读取 ${file.displayName}")
            input.use { source -> cacheFile.outputStream().use(source::copyTo) }
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.share-files",
                cacheFile,
            )
            ShareableFile(uri, file.displayName, file.mimeType)
        }
    }

    private fun trimOldCache(root: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::deleteRecursively)
    }

    private fun fail(taskId: String, phase: String, error: Throwable): ShareLaunchResult {
        logFailure(taskId, phase, error)
        val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
        return ShareLaunchResult(false, "分享失败：$detail。请导出诊断日志")
    }

    private fun logFailure(taskId: String, phase: String, error: Throwable) {
        log(taskId, "SHARE_FAILED", JSONObject().apply {
            put("phase", phase)
            put("error", error.javaClass.name)
            put("message", error.message.orEmpty())
        })
    }

    private fun log(taskId: String, name: String, details: JSONObject) {
        runCatching { logger.event(taskId, "SHARE", name, details) }
    }

    private companion object {
        const val SHARE_CACHE_DIRECTORY = "share"
        const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
