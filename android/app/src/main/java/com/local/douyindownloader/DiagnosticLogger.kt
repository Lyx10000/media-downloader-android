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

object Redactor {
    private val secret = Regex(
        "(?i)(\"?(?:cookie|a_bogus|msToken|signature|token|odin_tt|ttwid)\"?\\s*[:=]\\s*\"?)([^\"\\s,;&}]+)",
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
            put("parser_version", "android-core-3")
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

    fun readRecent(maxChars: Int = 24_000): String = listFiles()
        .filter { it.extension == "jsonl" }
        .take(8)
        .joinToString("\n") { file ->
            "===== ${file.name} =====\n" + file.readText().takeLast(maxChars / 8)
        }

    fun clear() {
        root.listFiles()?.forEach(File::delete)
    }

    fun export(): String {
        val displayName = "diagnostic-${System.currentTimeMillis()}.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/DouyinDownloader/diagnostics",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建诊断 ZIP")
        resolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                val manifest = JSONObject().apply {
                    put("app_version", BuildConfig.VERSION_NAME)
                    put("parser_version", "android-core-3")
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
        } ?: error("无法写入诊断 ZIP")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
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
