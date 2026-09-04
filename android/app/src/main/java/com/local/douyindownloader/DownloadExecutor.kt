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
            val fallbackExtension = extensionFromUrl(urls.first(), "jpg")
            val provisional = File(folder, "image_${index + 1}.download")
            val label = "原图 ${index + 1}/${imageCandidates.size}"
            progress("准备下载$label", 0, true)
            download(
                taskId = taskId,
                urls = urls,
                target = provisional,
                label = label,
                referer = spec.result.referer,
                progress = progress,
            )
            val extension = provisional.inputStream().use { input ->
                val header = ByteArray(16)
                val count = input.read(header).coerceAtLeast(0)
                imageExtension(header.copyOf(count), fallbackExtension)
            }
            val name = "image_${index + 1}.$extension"
            val file = File(folder, name)
            check(provisional.renameTo(file)) { "无法按真实图片格式命名：$name" }
            files += file to name
        }
        spec.result.musicUrls.firstOrNull()?.let { url ->
            val name = "bgm_1.${extensionFromUrl(url, "m4a")}"
            val file = File(folder, name)
            progress("准备下载 BGM", 0, true)
            download(
                taskId = taskId,
                urls = spec.result.musicUrls,
                target = file,
                label = "BGM",
                referer = spec.result.referer,
                progress = progress,
            )
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
        val source = File(folder, "video_1_source.mp4")
        val videoTrack = File(folder, "video_1_video.mp4")
        val audioTrack = File(folder, "video_1_audio.m4a")
        val merged = File(folder, "video_1.mp4")

        progress("准备下载原始视频", 0, true)
        download(
            taskId = taskId,
            urls = variant.urls,
            target = source,
            label = "视频",
            referer = spec.result.referer,
            fallbackTotalBytes = variant.size.takeIf {
                it > 0L && variant.sizeSource != "estimated"
            } ?: -1L,
            progress = progress,
        )
        progress("分析音视频轨道", 0, true)
        val sourceProbe = MediaTrackProcessor.probe(source)
        val probeText = sourceProbe.toString()
        val hasEmbeddedAudio = sourceProbe.any { track ->
            (track["mime"] as? String)?.startsWith("audio/") == true
        }
        val audioSource = chooseVideoAudioSource(
            hasEmbeddedAudio = hasEmbeddedAudio,
            hasSeparateAudio = audioUrls.isNotEmpty(),
        )
        logger.event(
            taskId,
            "MEDIA_PROCESS",
            "SOURCE_PROBED",
            JSONObject().apply {
                put("tracks", probeText)
                put("embedded_audio", hasEmbeddedAudio)
                put("separate_audio_urls", audioUrls.size)
                put("selected_audio_source", audioSource.name.lowercase())
            },
        )
        logger.saveMediaProbe(taskId, probeText)

        val files = when (audioSource) {
            VideoAudioSource.EMBEDDED -> processEmbeddedAudio(
                source = source,
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                mode = mode,
                progress = progress,
            )
            VideoAudioSource.SEPARATE -> processSeparateAudio(
                taskId = taskId,
                source = source,
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                merged = merged,
                audioUrls = audioUrls,
                referer = spec.result.referer,
                mode = mode,
                progress = progress,
            )
            VideoAudioSource.MISSING -> {
                if (mode == DownloadMode.AUDIO_ONLY) {
                    error("视频文件没有音频轨，也没有可用的独立音频地址")
                }
                logger.event(taskId, "MEDIA_PROCESS", "SILENT_VIDEO_PRESERVED", JSONObject().apply {
                    put("requested_mode", mode.wireValue)
                })
                listOf(source to videoTrack.name)
            }
        }
        return publishAll(taskId, spec, files, progress)
    }

    private suspend fun processEmbeddedAudio(
        source: File,
        videoTrack: File,
        audioTrack: File,
        mode: DownloadMode,
        progress: DownloadProgress,
    ): List<Pair<File, String>> {
        progress("使用视频内置原始音频", 0, true)
        return when (mode) {
            DownloadMode.MERGE_KEEP -> {
                MediaTrackProcessor.extractVideo(source, videoTrack)
                MediaTrackProcessor.extractAudio(source, audioTrack)
                listOf(
                    videoTrack to videoTrack.name,
                    audioTrack to audioTrack.name,
                    source to "video_1.mp4",
                )
            }
            DownloadMode.TRACKS -> {
                MediaTrackProcessor.extractVideo(source, videoTrack)
                MediaTrackProcessor.extractAudio(source, audioTrack)
                listOf(videoTrack to videoTrack.name, audioTrack to audioTrack.name)
            }
            DownloadMode.VIDEO_ONLY -> {
                MediaTrackProcessor.extractVideo(source, videoTrack)
                listOf(videoTrack to videoTrack.name)
            }
            DownloadMode.AUDIO_ONLY -> {
                MediaTrackProcessor.extractAudio(source, audioTrack)
                listOf(audioTrack to audioTrack.name)
            }
        }
    }

    private suspend fun processSeparateAudio(
        taskId: String,
        source: File,
        videoTrack: File,
        audioTrack: File,
        merged: File,
        audioUrls: List<String>,
        referer: String,
        mode: DownloadMode,
        progress: DownloadProgress,
    ): List<Pair<File, String>> {
        if (mode == DownloadMode.VIDEO_ONLY) {
            return listOf(source to videoTrack.name)
        }

        progress("准备下载独立音频轨", 0, true)
        download(
            taskId = taskId,
            urls = audioUrls,
            target = audioTrack,
            label = "独立音频",
            referer = referer,
            progress = progress,
        )
        return when (mode) {
            DownloadMode.MERGE_KEEP -> {
                progress("无损合并音视频", 0, true)
                logger.event(taskId, "MEDIA_PROCESS", "MUX_STARTED")
                MediaTrackProcessor.mux(source, audioTrack, merged)
                val mergedProbe = MediaTrackProcessor.probe(merged).toString()
                logger.saveMediaProbe(taskId, mergedProbe)
                listOf(
                    source to videoTrack.name,
                    audioTrack to audioTrack.name,
                    merged to merged.name,
                )
            }
            DownloadMode.TRACKS -> listOf(
                source to videoTrack.name,
                audioTrack to audioTrack.name,
            )
            DownloadMode.AUDIO_ONLY -> listOf(audioTrack to audioTrack.name)
            DownloadMode.VIDEO_ONLY -> error("不可达的视频下载模式")
        }
    }

    private suspend fun publishAll(
        taskId: String,
        spec: TaskSpec,
        files: List<Pair<File, String>>,
        progress: DownloadProgress,
    ): List<TaskOutput> {
        progress("保存到公共下载目录", 0, true)
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
        label: String,
        referer: String,
        fallbackTotalBytes: Long = -1L,
        progress: DownloadProgress,
    ) {
        var lastError: Throwable? = null
        val securedUrls = urls.mapIndexed { addressIndex, originalAddress ->
            secureDownloadUrl(originalAddress).also { address ->
                if (address != originalAddress) {
                    logger.event(taskId, "DOWNLOAD", "CDN_HTTPS_UPGRADED", JSONObject().apply {
                        put("cdn_index", addressIndex)
                        put("cdn_host", URL(address).host)
                    })
                }
            }
        }.distinct()
        securedUrls.forEachIndexed { addressIndex, address ->
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    val connection = URL(address).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 120_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    connection.setRequestProperty("Referer", referer)
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) error("CDN HTTP $status")
                        val total = connection.contentLengthLong.takeIf { it > 0L }
                            ?: fallbackTotalBytes
                        progress(
                            formatDownloadStatus(label, 0L, total, 0L),
                            0,
                            true,
                        )
                        target.outputStream().use { output ->
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(128 * 1024)
                                var downloaded = 0L
                                var lastReportedBytes = 0L
                                var lastReportedAt = System.nanoTime()

                                suspend fun reportDownloadProgress(force: Boolean) {
                                    val now = System.nanoTime()
                                    val elapsed = now - lastReportedAt
                                    if (downloaded == lastReportedBytes) return
                                    if (!force && elapsed < PROGRESS_REPORT_INTERVAL_NANOS) return
                                    val bytesPerSecond = if (elapsed > 0L) {
                                        ((downloaded - lastReportedBytes).toDouble() * 1_000_000_000L /
                                            elapsed).toLong().coerceAtLeast(0L)
                                    } else {
                                        0L
                                    }
                                    progress(
                                        formatDownloadStatus(
                                            label,
                                            downloaded,
                                            total,
                                            bytesPerSecond,
                                        ),
                                        downloadFileProgress(downloaded, total),
                                        true,
                                    )
                                    lastReportedBytes = downloaded
                                    lastReportedAt = now
                                }

                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    reportDownloadProgress(force = false)
                                }
                                reportDownloadProgress(force = true)
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

    companion object {
        private const val PROGRESS_REPORT_INTERVAL_NANOS = 750_000_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"
    }
}

internal fun imageExtension(header: ByteArray, fallback: String): String {
    fun startsWith(vararg bytes: Int): Boolean = bytes.indices.all { index ->
        header.getOrNull(index)?.toInt()?.and(0xff) == bytes[index]
    }
    val ascii = header.toString(Charsets.ISO_8859_1)
    return when {
        startsWith(0xff, 0xd8, 0xff) -> "jpg"
        startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
        ascii.startsWith("GIF87a") || ascii.startsWith("GIF89a") -> "gif"
        ascii.startsWith("RIFF") && ascii.drop(8).startsWith("WEBP") -> "webp"
        startsWith(0x42, 0x4d) -> "bmp"
        ascii.drop(4).startsWith("ftypavif") || ascii.drop(4).startsWith("ftypavis") -> "avif"
        ascii.drop(4).startsWith("ftypheic") || ascii.drop(4).startsWith("ftypheix") -> "heic"
        ascii.drop(4).startsWith("ftypheif") || ascii.drop(4).startsWith("ftypmif1") -> "heif"
        else -> fallback
    }
}

internal fun secureDownloadUrl(address: String): String {
    val parsed = runCatching { URL(address) }.getOrNull() ?: return address
    val host = parsed.host.lowercase()
    val hasUserInfo = runCatching { parsed.toURI().userInfo != null }.getOrDefault(true)
    if (!parsed.protocol.equals("http", ignoreCase = true) ||
        !host.endsWith(".xhscdn.com") || hasUserInfo
    ) {
        return address
    }
    return address.replaceFirst(HTTP_SCHEME, "https://")
}

private val HTTP_SCHEME = Regex("^http://", RegexOption.IGNORE_CASE)
