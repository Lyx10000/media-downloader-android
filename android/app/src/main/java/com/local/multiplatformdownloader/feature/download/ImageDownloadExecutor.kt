package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.download.MotionPhotoWriter
import com.local.multiplatformdownloader.core.download.imageExtension
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Singleton
internal class ImageDownloadExecutor @Inject constructor(
    private val logger: DiagnosticLogger,
    private val mediaTransferClient: MediaTransferClient,
    private val outputPublisher: OutputPublisher,
) {
    suspend fun execute(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        persistIntermediateOutputs: Boolean,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit,
        progress: DownloadProgress,
    ): DownloadExecutionResult {
        val files = mutableListOf<Pair<File, String>>()
        val downloadedImages = mutableListOf<File>()
        var warningCount = 0
        val imageCandidates = spec.result.imageCandidates.ifEmpty {
            spec.result.imageUrls.map(::listOf)
        }
        imageCandidates.forEachIndexed { index, urls ->
            val fallbackExtension = extensionFromUrl(urls.first(), "jpg")
            val provisional = File(folder, "image_${index + 1}.download")
            val label = "原图 ${index + 1}/${imageCandidates.size}"
            progress("准备下载$label", 0, true)
            mediaTransferClient.download(
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
            downloadedImages += file
        }
        spec.result.livePhotos.forEachIndexed { liveIndex, pair ->
            logger.event(taskId, "MEDIA_PROCESS", "LIVE_PHOTO_DETECTED", JSONObject().apply {
                put("pair_index", liveIndex)
                put("image_index", pair.imageIndex)
                put("video_variants", pair.videoVariants.size)
            })
            val motionVideo = File(folder, "live_${pair.imageIndex + 1}_motion.mp4")
            try {
                val variant = pair.videoVariants.firstOrNull()
                    ?: error("实况照片没有动态视频地址")
                progress("下载实况视频 ${liveIndex + 1}/${spec.result.livePhotos.size}", 0, true)
                mediaTransferClient.download(
                    taskId = taskId,
                    urls = variant.urls,
                    target = motionVideo,
                    label = "实况视频 ${liveIndex + 1}",
                    referer = spec.result.referer,
                    fallbackTotalBytes = variant.size.takeIf {
                        it > 0L && variant.sizeSource != "estimated"
                    } ?: -1L,
                    progress = progress,
                )
                files += motionVideo to motionVideo.name

                val sourceImage = downloadedImages.getOrNull(pair.imageIndex)
                    ?: error("实况照片对应的静态图片不存在")
                val motionPhoto = File(folder, "live_${pair.imageIndex + 1}.jpg")
                progress("合成实况照片 ${liveIndex + 1}/${spec.result.livePhotos.size}", 0, true)
                MotionPhotoWriter.compose(sourceImage, motionVideo, motionPhoto)
                files += motionPhoto to motionPhoto.name
                logger.event(taskId, "MEDIA_PROCESS", "LIVE_PHOTO_COMPOSED", JSONObject().apply {
                    put("pair_index", liveIndex)
                    put("image_index", pair.imageIndex)
                    put("bytes", motionPhoto.length())
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                warningCount += 1
                logger.event(taskId, "MEDIA_PROCESS", "LIVE_PHOTO_COMPOSE_FAILED", JSONObject().apply {
                    put("pair_index", liveIndex)
                    put("image_index", pair.imageIndex)
                    put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
                    put("motion_video_retained", motionVideo.isFile)
                })
            }
        }
        spec.result.musicUrls.firstOrNull()?.let { url ->
            val name = "bgm_1.${extensionFromUrl(url, "m4a")}"
            val file = File(folder, name)
            progress("准备下载 BGM", 0, true)
            mediaTransferClient.download(
                taskId = taskId,
                urls = spec.result.musicUrls,
                target = file,
                label = "BGM",
                referer = spec.result.referer,
                progress = progress,
            )
            files += file to name
        }
        return DownloadExecutionResult(
            outputs = outputPublisher.publishAll(
                taskId,
                spec,
                files,
                persistIntermediateOutputs,
                onPublishedOutputs,
                progress,
            ),
            warningCount = warningCount,
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
        } ?: fallback
    }
}

