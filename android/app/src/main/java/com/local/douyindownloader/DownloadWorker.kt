package com.local.douyindownloader

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val store = TaskStore(appContext)
    private val logger = DiagnosticLogger(appContext)
    private lateinit var taskId: String

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        taskId = inputData.getString(KEY_TASK_ID) ?: return@withContext Result.failure()
        val spec = store.getSpec(taskId) ?: return@withContext Result.failure()
        setForeground(createForeground("准备下载", 0))
        val taskFolder = File(applicationContext.cacheDir, "downloadTasks/$taskId").apply { mkdirs() }
        try {
            update("RUNNING", "准备下载", 0)
            logger.event(taskId, "DOWNLOAD", "TASK_STARTED", JSONObject().apply {
                put("aweme_id", spec.result.awemeId)
                put("kind", spec.result.kind)
                put("mode", spec.mode)
            })
            val outputs = if (spec.result.kind == "image") {
                downloadImages(spec, taskFolder)
            } else {
                downloadVideo(spec, taskFolder)
            }
            store.complete(taskId, outputs)
            logger.event(taskId, "COMPLETE", "TASK_COMPLETE", JSONObject().put("files", outputs.size))
            setForeground(createForeground("下载完成", 100))
            taskFolder.deleteRecursively()
            Result.success()
        } catch (cancelled: CancellationException) {
            store.update(taskId, "CANCELLED", "已取消", 0)
            logger.event(taskId, "DOWNLOAD", "TASK_CANCELLED")
            taskFolder.deleteRecursively()
            throw cancelled
        } catch (error: Throwable) {
            val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            store.update(taskId, "FAILED", "失败", 0, message)
            logger.event(taskId, "DOWNLOAD", "TASK_FAILED", JSONObject().apply {
                put("type", error.javaClass.name)
                put("message", message)
                put("cached_files", taskFolder.listFiles()?.map(File::getName).orEmpty())
            })
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForeground("等待下载", 0)

    private suspend fun downloadImages(spec: TaskSpec, folder: File): List<String> {
        val files = mutableListOf<Pair<File, String>>()
        spec.result.imageUrls.forEachIndexed { index, url ->
            val ext = extensionFromUrl(url, "jpg")
            val name = "image_${index + 1}.$ext"
            val file = File(folder, name)
            update("RUNNING", "下载原图 ${index + 1}/${spec.result.imageUrls.size}", percent(index, spec.result.imageUrls.size))
            download(listOf(url), file)
            files += file to name
        }
        spec.result.musicUrls.firstOrNull()?.let { url ->
            val name = "bgm_1.${extensionFromUrl(url, "m4a")}" 
            val file = File(folder, name)
            update("RUNNING", "下载 BGM", 90)
            download(spec.result.musicUrls, file)
            files += file to name
        }
        return publishAll(spec, files)
    }

    private suspend fun downloadVideo(spec: TaskSpec, folder: File): List<String> {
        val variant = spec.result.variants.getOrNull(spec.variantIndex)
            ?: error("没有可下载的视频档位")
        val mode = spec.mode
        val audioUrls = spec.result.audioUrls
        return if (audioUrls.isNotEmpty()) {
            val videoTrack = File(folder, "video_1_video.mp4")
            val audioTrack = File(folder, "video_1_audio.m4a")
            val merged = File(folder, "video_1.mp4")
            val files = mutableListOf<Pair<File, String>>()
            if (mode in setOf("merge_keep", "tracks", "video_only")) {
                update("RUNNING", "下载视频轨", 10)
                download(variant.urls, videoTrack)
            }
            if (mode in setOf("merge_keep", "tracks", "audio_only")) {
                update("RUNNING", "下载音频轨", 48)
                download(audioUrls, audioTrack)
            }
            when (mode) {
                "merge_keep" -> {
                    update("RUNNING", "无损合并音视频", 82)
                    logger.event(taskId, "MEDIA_PROCESS", "MUX_STARTED")
                    MediaTrackProcessor.mux(videoTrack, audioTrack, merged)
                    logger.saveMediaProbe(taskId, MediaTrackProcessor.probe(merged).toString())
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                    files += merged to merged.name
                }
                "tracks" -> {
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                }
                "video_only" -> files += videoTrack to videoTrack.name
                "audio_only" -> files += audioTrack to audioTrack.name
            }
            publishAll(spec, files)
        } else {
            val source = File(folder, "video_1.mp4")
            val videoTrack = File(folder, "video_1_video.mp4")
            val audioTrack = File(folder, "video_1_audio.m4a")
            update("RUNNING", "下载原始音视频", 15)
            download(variant.urls, source)
            logger.event(taskId, "MEDIA_PROCESS", "SOURCE_PROBED", JSONObject().apply {
                put("tracks", MediaTrackProcessor.probe(source).toString())
            })
            logger.saveMediaProbe(taskId, MediaTrackProcessor.probe(source).toString())
            val files = mutableListOf<Pair<File, String>>()
            update("RUNNING", "无损拆分轨道", 78)
            when (mode) {
                "merge_keep" -> {
                    MediaTrackProcessor.extractVideo(source, videoTrack)
                    MediaTrackProcessor.extractAudio(source, audioTrack)
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                    files += source to source.name
                }
                "tracks" -> {
                    MediaTrackProcessor.extractVideo(source, videoTrack)
                    MediaTrackProcessor.extractAudio(source, audioTrack)
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                }
                "video_only" -> {
                    MediaTrackProcessor.extractVideo(source, videoTrack)
                    files += videoTrack to videoTrack.name
                }
                "audio_only" -> {
                    MediaTrackProcessor.extractAudio(source, audioTrack)
                    files += audioTrack to audioTrack.name
                }
            }
            publishAll(spec, files)
        }
    }

    private suspend fun publishAll(spec: TaskSpec, files: List<Pair<File, String>>): List<String> {
        update("RUNNING", "保存到公共下载目录", 94)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        }.format(Date(spec.createdAt))
        return files.map { (file, name) ->
            PublicStorage.publish(applicationContext, file, timestamp, name).toString()
        }
    }

    private suspend fun download(urls: List<String>, target: File) {
        var lastError: Throwable? = null
        urls.distinct().forEachIndexed { addressIndex, address ->
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 120_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    connection.setRequestProperty("Referer", "https://www.douyin.com/")
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) error("CDN HTTP $status")
                        val total = connection.contentLengthLong
                        target.outputStream().use { output ->
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(128 * 1024)
                                var downloaded = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    if (total > 0) {
                                        val progress = (downloaded * 65 / total).toInt().coerceIn(1, 65)
                                        setForeground(createForeground("正在下载 ${target.name}", progress))
                                        if (progress % 5 == 0) {
                                            store.update(taskId, "RUNNING", "正在下载 ${target.name}", progress)
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    logger.event(taskId, "DOWNLOAD", "FILE_DOWNLOADED", JSONObject().apply {
                        put("name", target.name)
                        put("bytes", target.length())
                        put("cdn_index", addressIndex)
                        put("cdn_host", URL(address).host)
                    })
                    return
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    lastError = error
                    target.delete()
                    logger.event(taskId, "DOWNLOAD", "CDN_ATTEMPT_FAILED", JSONObject().apply {
                        put("cdn_index", addressIndex)
                        put("attempt", attempt + 1)
                        put("message", error.message ?: error.javaClass.simpleName)
                    })
                }
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载地址")
    }

    private suspend fun update(status: String, stage: String, progress: Int) {
        store.update(taskId, status, stage, progress)
        setForeground(createForeground(stage, progress))
    }

    private fun createForeground(stage: String, progress: Int): ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("抖音下载器")
            .setContentText(stage)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress, progress <= 0)
            .build()
        return ForegroundInfo(
            taskId.hashCode().and(0x7fffffff),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun extensionFromUrl(url: String, fallback: String): String {
        val clean = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase()
        return clean.takeIf {
            it in setOf(
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
                "m4a", "mp3", "aac",
            )
        }
            ?: fallback
    }

    private fun percent(index: Int, count: Int): Int = if (count <= 0) 0 else index * 80 / count

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val CHANNEL_ID = "douyin_downloads"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    }
}
