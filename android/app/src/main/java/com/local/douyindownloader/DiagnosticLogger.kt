package com.local.douyindownloader

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal const val PARSER_VERSION = "kotlin-core-4"

internal data class DiagnosticExportResult(
    val uri: String,
    val displayName: String,
    val relativePath: String,
)

object Redactor {
    private val secret = Regex(
        "(?i)(\"?(?:cookie|a_bogus|msToken|signature|token|odin_tt|ttwid|SESSDATA|bili_jct|DedeUserID|buvid[0-9a-z_]*|w_rid)\"?\\s*[:=]\\s*\"?)([^\"\\s,;&}]+)",
    )
    private val urlQuery = Regex("(https?://[^\\s\"'?]+)\\?[^\\s\"']+")

    fun sanitize(value: String): String = value
        .replace(secret) { match -> "${match.groupValues[1]}<redacted>" }
        .replace(urlQuery, "$1?<redacted>")
        .take(16_384)
}

@Singleton
class DiagnosticLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root = File(context.filesDir, "diagnostics").apply { mkdirs() }

    @Synchronized
    fun event(
        taskId: String,
        stage: String,
        name: String,
        details: JSONObject = JSONObject(),
    ) {
        val record = JSONObject().apply {
            put("timestamp_ms", System.currentTimeMillis())
            put("task_id", taskId)
            put("stage", stage)
            put("event", name)
            put("app_version", BuildConfig.VERSION_NAME)
            put("parser_version", PARSER_VERSION)
            put("details", JSONObject(Redactor.sanitize(details.toString())))
        }
        File(root, "$taskId.jsonl").appendText(record.toString() + "\n")
        trimOldLogs()
    }

    fun saveResponseShape(taskId: String, shape: String) {
        File(root, "$taskId-response-shape.json").writeText(Redactor.sanitize(shape))
    }

    fun saveMediaProbe(taskId: String, probe: String) {
        File(root, "$taskId-media-probe.json").writeText(Redactor.sanitize(probe))
    }

    fun uncaughtException(thread: Thread, error: Throwable) {
        event("app-crash", "CRASH", "UNCAUGHT_EXCEPTION", JSONObject().apply {
            put("thread", thread.name)
            put("error", error.javaClass.name)
            put("message", error.message.orEmpty())
            put("stack", error.stackTraceToString())
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
        })
    }

    fun listFiles(): List<File> = root.listFiles()?.sortedByDescending(File::lastModified).orEmpty()

    fun readRecent(maxChars: Int = 256_000): String {
        val files = listFiles()
            .filter { it.extension == "jsonl" }
            .take(8)
        if (files.isEmpty()) return ""
        val perFileLimit = (maxChars / files.size).coerceAtLeast(8_000)
        return files.joinToString("\n") { file ->
            "===== ${file.name} =====\n" + previewJsonLog(file.readText(), perFileLimit)
        }
    }

    fun clear() {
        root.listFiles()?.forEach(File::delete)
    }

    @Synchronized
    internal fun export(): DiagnosticExportResult {
        val displayName = "diagnostic-${System.currentTimeMillis()}.zip"
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/DouyinDownloader/diagnostics"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建诊断 ZIP")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                ZipOutputStream(output).use { zip ->
                    val manifest = JSONObject().apply {
                        put("app_version", BuildConfig.VERSION_NAME)
                        put("parser_version", PARSER_VERSION)
                        put("manufacturer", Build.MANUFACTURER)
                        put("model", Build.MODEL)
                        put("android", Build.VERSION.RELEASE)
                        put("sdk", Build.VERSION.SDK_INT)
                        put("webview", WebView.getCurrentWebViewPackage()?.versionName ?: "unknown")
                        put("exported_at_ms", System.currentTimeMillis())
                    }.toString(2)
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(manifest.toByteArray())
                    zip.closeEntry()

                    listFiles().forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: error("系统拒绝写入诊断 ZIP")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) {
                error("诊断 ZIP 写入完成，但系统未能发布文件")
            }
            return DiagnosticExportResult(uri.toString(), displayName, relativePath)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun trimOldLogs() {
        val files = listFiles().sortedByDescending(File::lastModified)
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        var total = 0L
        files.forEach { file ->
            total += file.length()
            if (file.lastModified() < cutoff || total > 20L * 1024 * 1024) file.delete()
        }
    }
}

internal fun prettyPrintJsonLines(value: String): String = value
    .lineSequence()
    .filter(String::isNotBlank)
    .joinToString("\n\n") { line ->
        runCatching { JSONObject(line).toString(2) }.getOrDefault(line)
    }

internal fun previewJsonLog(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return prettyPrintJsonLines(value)
    val safeLimit = maxChars.coerceAtLeast(256)
    val headLimit = safeLimit / 2
    val tailLimit = safeLimit - headLimit
    val head = value.take(headLimit).substringBeforeLast('\n').ifBlank {
        value.take(headLimit)
    }
    val tailChunk = value.takeLast(tailLimit)
    val tail = tailChunk.substringAfter('\n', tailChunk)
    val marker = "……日志过长，中间部分仅在导出的 ZIP 中保留……"
    return listOf(prettyPrintJsonLines(head), marker, prettyPrintJsonLines(tail))
        .filter(String::isNotBlank)
        .joinToString("\n\n")
}
