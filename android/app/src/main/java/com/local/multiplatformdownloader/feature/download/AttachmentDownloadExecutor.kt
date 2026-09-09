package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.download.imageExtension
import com.local.multiplatformdownloader.core.model.AttachmentSelection
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaAttachment
import com.local.multiplatformdownloader.core.model.MediaAttachmentKind
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AttachmentDownloadExecutor @Inject constructor(
    private val mediaTransferClient: MediaTransferClient,
    private val outputPublisher: OutputPublisher,
    private val videoDownloadExecutor: VideoDownloadExecutor,
) {
    suspend fun execute(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        persistIntermediateOutputs: Boolean,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit,
        progress: DownloadProgress,
    ): DownloadExecutionResult {
        val published = mutableListOf<TaskOutput>()
        val selections = spec.attachmentSelections.associateBy(AttachmentSelection::attachmentId)
        val ordered = spec.result.attachments.sortedBy(MediaAttachment::index)
        ordered.forEachIndexed { position, attachment ->
            val ordinal = (position + 1).toString().padStart(2, '0')
            val files = when (attachment.kind) {
                MediaAttachmentKind.IMAGE -> {
                    val candidates = attachment.imageCandidates
                    if (candidates.isEmpty()) error("第 ${position + 1} 个附件没有图片下载地址")
                    val provisional = File(folder, "media_${ordinal}_image.download")
                    progress("准备下载图片 ${position + 1}/${ordered.size}", 0, true)
                    mediaTransferClient.download(
                        taskId = taskId,
                        urls = candidates,
                        target = provisional,
                        label = "图片 ${position + 1}/${ordered.size}",
                        referer = spec.result.referer,
                        progress = progress,
                    )
                    val fallback = extensionFromUrl(candidates.first(), "jpg")
                    val extension = provisional.inputStream().use { input ->
                        val header = ByteArray(16)
                        val count = input.read(header).coerceAtLeast(0)
                        imageExtension(header.copyOf(count), fallback)
                    }
                    val name = "media_${ordinal}_image.$extension"
                    val image = File(folder, name)
                    check(provisional.renameTo(image)) { "无法按真实图片格式命名：$name" }
                    listOf(image to name)
                }
                MediaAttachmentKind.VIDEO,
                MediaAttachmentKind.GIF,
                -> {
                    val selectedIndex = selections[attachment.id]?.variantIndex ?: 0
                    val variant = attachment.variants.getOrNull(selectedIndex)
                        ?: attachment.variants.firstOrNull()
                        ?: error("第 ${position + 1} 个附件没有可下载的 MP4 档位")
                    videoDownloadExecutor.prepareFiles(
                        taskId = taskId,
                        spec = spec,
                        variant = variant,
                        mode = if (attachment.kind == MediaAttachmentKind.GIF) {
                            DownloadMode.VIDEO_ONLY
                        } else {
                            spec.mode
                        },
                        audioUrls = emptyList(),
                        prefix = "media_${ordinal}_video",
                        finalDisplayName = if (attachment.kind == MediaAttachmentKind.GIF) {
                            "media_${ordinal}_gif.mp4"
                        } else {
                            "media_${ordinal}_video.mp4"
                        },
                        folder = folder,
                        progress = progress,
                    )
                }
            }
            files.forEach { (file, name) ->
                published += outputPublisher.publish(file, spec, name)
                outputPublisher.recordPublishedOutputs(
                    taskId,
                    published,
                    persistIntermediateOutputs,
                    onPublishedOutputs,
                )
            }
        }
        return DownloadExecutionResult(published)
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
