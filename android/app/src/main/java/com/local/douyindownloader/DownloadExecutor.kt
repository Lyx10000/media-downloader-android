package com.local.douyindownloader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

typealias DownloadProgress = suspend (stage: String, progress: Int, persist: Boolean) -> Unit

@Singleton
class DownloadExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
) {
    suspend fun execute(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        progress: DownloadProgress,
    ): List<TaskOutput> = if (spec.result.kind == MediaKind.IMAGE) {
        downloadImages(taskId, spec, folder, progress)
    } else {
        downloadVideo(taskId, spec, folder, progress)
    }

    private suspend fun downloadImages(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        progress: DownloadProgress,
    ): List<TaskOutput> {
        val files = mutableListOf<Pair<File, String>>()
        val imageCandidates = spec.result.imageCandidates.ifEmpty {
            spec.result.imageUrls.map(::listOf)
        }
        imageCandidates.forEachIndexed { index, urls ->
            val name = "image_${index + 1}.${extensionFromUrl(urls.first(), "jpg")}"
            val file = File(folder, name)
            progress("下载原图 ${index + 1}/${imageCandidates.size}", percent(index, imageCandidates.size), true)
            download(taskId, urls, file, progress)
            files += file to name
        }
        spec.result.musicUrls.firstOrNull()?.let { url ->
            val name = "bgm_1.${extensionFromUrl(url, "m4a")}"
            val file = File(folder, name)
            progress("下载 BGM", 90, true)
            download(taskId, spec.result.musicUrls, file, progress)
            files += file to name
        }
        return publishAll(taskId, spec, files, progress)
    }

    private suspend fun downloadVideo(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        progress: DownloadProgress,
    ): List<TaskOutput> {
        val variant = spec.result.variants.getOrNull(spec.variantIndex)
            ?: error("没有可下载的视频档位")
        val mode = spec.mode
        val audioUrls = spec.result.audioUrls
        return if (audioUrls.isNotEmpty()) {
            val videoTrack = File(folder, "video_1_video.mp4")
            val audioTrack = File(folder, "video_1_audio.m4a")
            val merged = File(folder, "video_1.mp4")
            val files = mutableListOf<Pair<File, String>>()
            if (mode in setOf(DownloadMode.MERGE_KEEP, DownloadMode.TRACKS, DownloadMode.VIDEO_ONLY)) {
                progress("下载视频轨", 10, true)
                download(taskId, variant.urls, videoTrack, progress)
            }
            if (mode in setOf(DownloadMode.MERGE_KEEP, DownloadMode.TRACKS, DownloadMode.AUDIO_ONLY)) {
                progress("下载音频轨", 48, true)
                download(taskId, audioUrls, audioTrack, progress)
            }
            when (mode) {
                DownloadMode.MERGE_KEEP -> {
                    progress("无损合并音视频", 82, true)
                    logger.event(taskId, "MEDIA_PROCESS", "MUX_STARTED")
                    MediaTrackProcessor.mux(videoTrack, audioTrack, merged)
                    logger.saveMediaProbe(taskId, MediaTrackProcessor.probe(merged).toString())
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                    files += merged to merged.name
                }
                DownloadMode.TRACKS -> {
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                }
                DownloadMode.VIDEO_ONLY -> files += videoTrack to videoTrack.name
                DownloadMode.AUDIO_ONLY -> files += audioTrack to audioTrack.name
            }
            publishAll(taskId, spec, files, progress)
        } else {
            val source = File(folder, "video_1.mp4")
            val videoTrack = File(folder, "video_1_video.mp4")
            val audioTrack = File(folder, "video_1_audio.m4a")
            progress("下载原始音视频", 15, true)
            download(taskId, variant.urls, source, progress)
            val probe = MediaTrackProcessor.probe(source).toString()
            logger.event(
                taskId,
                "MEDIA_PROCESS",
                "SOURCE_PROBED",
                JSONObject().put("tracks", probe),
            )
            logger.saveMediaProbe(taskId, probe)
            val files = mutableListOf<Pair<File, String>>()
            progress("无损拆分轨道", 78, true)
            when (mode) {
                DownloadMode.MERGE_KEEP -> {
                    MediaTrackProcessor.extractVideo(source, videoTrack)
                    MediaTrackProcessor.extractAudio(source, audioTrack)
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                    files += source to source.name
                }
                DownloadMode.TRACKS -> {
                    MediaTrackProcessor.extractVideo(source, videoTrack)
                    MediaTrackProcessor.extractAudio(source, audioTrack)
                    files += videoTrack to videoTrack.name
                    files += audioTrack to audioTrack.name
                }
                DownloadMode.VIDEO_ONLY -> {
                    MediaTrackProcessor.extractVideo(source, videoTrack)
                    files += videoTrack to videoTrack.name
                }
                DownloadMode.AUDIO_ONLY -> {
                    MediaTrackProcessor.extractAudio(source, audioTrack)
                    files += audioTrack to audioTrack.name
                }
            }
            publishAll(taskId, spec, files, progress)
        }
    }

    private suspend fun publishAll(
        taskId: String,
        spec: TaskSpec,
        files: List<Pair<File, String>>,
        progress: DownloadProgress,
    ): List<TaskOutput> {
        progress("保存到公共下载目录", 94, true)
        val published = mutableListOf<TaskOutput>()
        files.forEach { (file, name) ->
            published += PublicStorage.publish(context, file, spec, name)
            repository.replaceOutputs(taskId, published)
        }
        return published
    }

    private suspend fun download(
        taskId: String,
        urls: List<String>,
        target: File,
        progress: DownloadProgress,
    ) {
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
                                        val value = (downloaded * 65 / total).toInt().coerceIn(1, 65)
                                        progress("正在下载 ${target.name}", value, value % 5 == 0)
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

    private fun extensionFromUrl(url: String, fallback: String): String {
        val clean = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase()
        return clean.takeIf {
            it in setOf(
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
                "m4a", "mp3", "aac",
            )
        } ?: fallback
    }

    private fun percent(index: Int, count: Int): Int = if (count <= 0) 0 else index * 80 / count

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    }
}
