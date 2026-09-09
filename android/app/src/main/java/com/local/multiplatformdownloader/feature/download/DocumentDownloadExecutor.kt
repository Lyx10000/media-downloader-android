package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.download.imageExtension
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.DocumentAssetKind
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.feature.document.MarkdownRenderer
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuCommentExporter
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Singleton
internal class DocumentDownloadExecutor @Inject constructor(
    private val logger: DiagnosticLogger,
    private val zhihuCommentExporter: ZhihuCommentExporter,
    private val mediaTransferClient: MediaTransferClient,
    private val outputPublisher: OutputPublisher,
) {
    suspend fun execute(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        persistIntermediateOutputs: Boolean,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit,
        cookieHeader: String,
        progress: DownloadProgress,
    ): DownloadExecutionResult {
        val document = spec.result.document ?: error("知乎文档内容不存在")
        val mediaFolder = File(folder, "media").apply {
            if (!mkdirs() && !isDirectory) error("无法创建文档媒体缓存目录")
        }
        val outputs = mutableListOf<TaskOutput>()
        val localPaths = linkedMapOf<String, String>()
        val failures = linkedMapOf<String, String>()
        var imageIndex = 0
        var videoIndex = 0

        document.assets.forEachIndexed { assetIndex, asset ->
            val position = "${assetIndex + 1}/${document.assets.size}"
            try {
                val (file, name) = when (asset.kind) {
                    DocumentAssetKind.IMAGE -> {
                        imageIndex += 1
                        val candidates = asset.candidateUrls
                        if (candidates.isEmpty()) error("没有图片下载地址")
                        val stem = "image_${imageIndex.toString().padStart(3, '0')}"
                        val provisional = File(mediaFolder, "$stem.download")
                        progress("下载文档图片 $position", 0, true)
                        mediaTransferClient.download(
                            taskId = taskId,
                            urls = candidates,
                            target = provisional,
                            label = "图片 $position",
                            referer = spec.result.referer,
                            progress = progress,
                        )
                        val fallback = extensionFromUrl(candidates.first(), "jpg")
                        val extension = provisional.inputStream().use { input ->
                            val header = ByteArray(16)
                            val count = input.read(header).coerceAtLeast(0)
                            imageExtension(header.copyOf(count), fallback)
                        }
                        val name = "$stem.$extension"
                        val target = File(mediaFolder, name)
                        check(provisional.renameTo(target)) { "无法按真实图片格式命名：$name" }
                        target to name
                    }
                    DocumentAssetKind.VIDEO -> {
                        videoIndex += 1
                        val variant = asset.variants.firstOrNull()
                            ?: error("没有内嵌视频下载档位")
                        val name = "video_${videoIndex.toString().padStart(3, '0')}.mp4"
                        val target = File(mediaFolder, name)
                        progress("下载文档视频 $position", 0, true)
                        mediaTransferClient.download(
                            taskId = taskId,
                            urls = variant.urls,
                            target = target,
                            label = "视频 $position",
                            referer = spec.result.referer,
                            fallbackTotalBytes = variant.size.takeIf {
                                it > 0L && variant.sizeSource != "estimated"
                            } ?: -1L,
                            progress = progress,
                        )
                        target to name
                    }
                }
                val output = outputPublisher.publish(
                    source = file,
                    spec = spec,
                    displayName = name,
                    relativeDirectory = "media",
                )
                outputs += output
                localPaths[asset.id] = "media/$name"
                outputPublisher.recordPublishedOutputs(
                    taskId,
                    outputs,
                    persistIntermediateOutputs,
                    onPublishedOutputs,
                )
                if (asset.kind == DocumentAssetKind.VIDEO && asset.coverUrls.isNotEmpty()) {
                    runCatching {
                        val stem = "video_${videoIndex.toString().padStart(3, '0')}_cover"
                        val provisional = File(mediaFolder, "$stem.download")
                        mediaTransferClient.download(
                            taskId = taskId,
                            urls = asset.coverUrls,
                            target = provisional,
                            label = "视频封面 $position",
                            referer = spec.result.referer,
                            progress = progress,
                        )
                        val fallback = extensionFromUrl(asset.coverUrls.first(), "jpg")
                        val extension = provisional.inputStream().use { input ->
                            val header = ByteArray(16)
                            val count = input.read(header).coerceAtLeast(0)
                            imageExtension(header.copyOf(count), fallback)
                        }
                        val coverName = "$stem.$extension"
                        val cover = File(mediaFolder, coverName)
                        check(provisional.renameTo(cover)) { "无法按真实图片格式命名：$coverName" }
                        outputs += outputPublisher.publish(cover, spec, coverName, "media")
                        outputPublisher.recordPublishedOutputs(
                            taskId,
                            outputs,
                            persistIntermediateOutputs,
                            onPublishedOutputs,
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        logger.event(taskId, "DOWNLOAD", "DOCUMENT_VIDEO_COVER_SKIPPED", JSONObject().apply {
                            put("asset_id", asset.id)
                            put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
                        })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val reason = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                failures[asset.id] = reason
                logger.event(taskId, "DOWNLOAD", "DOCUMENT_ASSET_FAILED", JSONObject().apply {
                    put("asset_id", asset.id)
                    put("asset_kind", asset.kind.wireValue)
                    put("message", reason)
                })
            }
        }

        var commentWarningCount = 0
        val commentRequest = spec.zhihuCommentRequest
        if (commentRequest != null) {
            progress("下载回答评论", 0, true)
            val comments = File(folder, "comments.md")
            val commentResult = zhihuCommentExporter.export(commentRequest, cookieHeader, comments)
            outputs += outputPublisher.publish(comments, spec, "comments.md")
            outputPublisher.recordPublishedOutputs(taskId, outputs, persistIntermediateOutputs, onPublishedOutputs)
            if (commentResult.incomplete) commentWarningCount += 1
            logger.event(taskId, "COMMENTS", "COMMENTS_EXPORTED", JSONObject().apply {
                put("answer_id", commentRequest.answerId)
                put("comments", commentResult.count)
                put("incomplete", commentResult.incomplete)
                put("message", commentResult.warning)
            })
        }

        progress("生成 Markdown 文档", 0, true)
        val markdownName = document.type.fileName
        val markdown = File(folder, markdownName)
        val rendered = MarkdownRenderer.render(document, localPaths, failures)
        markdown.writeText(
            if (commentRequest == null) rendered else {
                rendered.trimEnd() + "\n\n---\n\n[查看全部评论](comments.md)\n"
            },
            Charsets.UTF_8,
        )
        val markdownOutput = outputPublisher.publish(markdown, spec, markdownName)
        outputs.add(0, markdownOutput)
        outputPublisher.recordPublishedOutputs(taskId, outputs, persistIntermediateOutputs, onPublishedOutputs)
        logger.event(taskId, "DOWNLOAD", "DOCUMENT_GENERATED", JSONObject().apply {
            put("assets", document.assets.size)
            put("downloaded", localPaths.size)
            put("failed", failures.size)
        })
        return DownloadExecutionResult(
            outputs,
            failures.size + document.warnings.size + commentWarningCount,
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

