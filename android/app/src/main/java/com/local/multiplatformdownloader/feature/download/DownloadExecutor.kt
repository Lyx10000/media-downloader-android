package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.download.AcceleratedDownloader
import com.local.multiplatformdownloader.core.download.AdaptiveDownloadController
import com.local.multiplatformdownloader.core.download.MediaTrackProcessor
import com.local.multiplatformdownloader.core.download.MotionPhotoWriter
import com.local.multiplatformdownloader.core.download.VideoAudioSource
import com.local.multiplatformdownloader.core.download.calculateSampledTransferSpeed
import com.local.multiplatformdownloader.core.download.chooseVideoAudioSource
import com.local.multiplatformdownloader.core.download.downloadFileProgress
import com.local.multiplatformdownloader.core.download.formatDownloadStatus
import com.local.multiplatformdownloader.core.download.imageExtension
import com.local.multiplatformdownloader.core.download.secureDownloadUrl
import com.local.multiplatformdownloader.core.download.shouldAccelerateDownload

import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.download.DownloadTrackKind
import com.local.multiplatformdownloader.core.download.TrackDownloadProgressRegistry
import com.local.multiplatformdownloader.core.download.combinedTrackProgress
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.AttachmentSelection
import com.local.multiplatformdownloader.core.model.DocumentAssetKind
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaAttachment
import com.local.multiplatformdownloader.core.model.MediaAttachmentKind
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.core.network.MediaRequestProfile
import com.local.multiplatformdownloader.core.storage.PublicStorage
import com.local.multiplatformdownloader.feature.document.MarkdownRenderer
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuCommentExporter
import com.local.multiplatformdownloader.platform.bilibili.BilibiliDeferredResolver

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class DownloadExecutionResult(
    val outputs: List<TaskOutput>,
    val warningCount: Int = 0,
)

@Singleton
class DownloadExecutor @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val zhihuCommentExporter: ZhihuCommentExporter,
    private val bilibiliDeferredResolver: BilibiliDeferredResolver,
    private val trackProgressRegistry: TrackDownloadProgressRegistry,
    private val adaptiveDownloadController: AdaptiveDownloadController,
) {
    private val acceleratedDownloader = AcceleratedDownloader()

    suspend fun execute(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        persistIntermediateOutputs: Boolean = true,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit = {},
        cookieHeader: String = "",
        progress: DownloadProgress,
    ): DownloadExecutionResult = adaptiveDownloadController.withTaskPermit(taskId, spec.result.platform) {
        val readySpec = bilibiliDeferredResolver.resolve(spec, progress)
        if (spec.result.attachments.isNotEmpty()) {
            downloadAttachments(
                taskId,
                spec,
                folder,
                persistIntermediateOutputs,
                onPublishedOutputs,
                progress,
            )
        } else when (spec.result.kind) {
            MediaKind.IMAGE -> downloadImages(
                taskId,
                spec,
                folder,
                persistIntermediateOutputs,
                onPublishedOutputs,
                progress,
            )
            MediaKind.VIDEO -> DownloadExecutionResult(
                downloadVideo(
                    taskId,
                    readySpec,
                    folder,
                    persistIntermediateOutputs,
                    onPublishedOutputs,
                    progress,
                ),
            )
            MediaKind.DOCUMENT -> downloadDocument(
                taskId,
                spec,
                folder,
                persistIntermediateOutputs,
                onPublishedOutputs,
                cookieHeader,
                progress,
            )
        }
    }

    private suspend fun downloadAttachments(
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
                    download(
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
                    prepareVideoFiles(
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
                published += PublicStorage.publish(context, file, spec, name)
                recordPublishedOutputs(
                    taskId,
                    published,
                    persistIntermediateOutputs,
                    onPublishedOutputs,
                )
            }
        }
        return DownloadExecutionResult(published)
    }

    private suspend fun downloadDocument(
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
                        download(
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
                        download(
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
                val output = PublicStorage.publish(
                    context = context,
                    source = file,
                    spec = spec,
                    displayName = name,
                    relativeDirectory = "media",
                )
                outputs += output
                localPaths[asset.id] = "media/$name"
                recordPublishedOutputs(
                    taskId,
                    outputs,
                    persistIntermediateOutputs,
                    onPublishedOutputs,
                )
                if (asset.kind == DocumentAssetKind.VIDEO && asset.coverUrls.isNotEmpty()) {
                    runCatching {
                        val stem = "video_${videoIndex.toString().padStart(3, '0')}_cover"
                        val provisional = File(mediaFolder, "$stem.download")
                        download(
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
                        outputs += PublicStorage.publish(context, cover, spec, coverName, "media")
                        recordPublishedOutputs(
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
            outputs += PublicStorage.publish(context, comments, spec, "comments.md")
            recordPublishedOutputs(taskId, outputs, persistIntermediateOutputs, onPublishedOutputs)
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
        val markdownOutput = PublicStorage.publish(context, markdown, spec, markdownName)
        outputs.add(0, markdownOutput)
        recordPublishedOutputs(taskId, outputs, persistIntermediateOutputs, onPublishedOutputs)
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

    private suspend fun downloadImages(
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
                download(
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
        return DownloadExecutionResult(
            outputs = publishAll(
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

    private suspend fun downloadVideo(
        taskId: String,
        spec: TaskSpec,
        folder: File,
        persistIntermediateOutputs: Boolean,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit,
        progress: DownloadProgress,
    ): List<TaskOutput> {
        val variant = spec.result.variants.getOrNull(spec.variantIndex)
            ?: error("没有可下载的视频档位")
        val files = prepareVideoFiles(
            taskId = taskId,
            spec = spec,
            variant = variant,
            mode = spec.mode,
            audioUrls = spec.result.audioUrls,
            prefix = "video_1",
            finalDisplayName = "video_1.mp4",
            folder = folder,
            progress = progress,
        )
        return publishAll(
            taskId,
            spec,
            files,
            persistIntermediateOutputs,
            onPublishedOutputs,
            progress,
        )
    }

    private suspend fun prepareVideoFiles(
        taskId: String,
        spec: TaskSpec,
        variant: MediaVariant,
        mode: DownloadMode,
        audioUrls: List<String>,
        prefix: String,
        finalDisplayName: String,
        folder: File,
        progress: DownloadProgress,
    ): List<Pair<File, String>> {
        val requestProfile = MediaRequestProfile.forPlatform(spec.result.platform, spec.result.referer)
        if (spec.result.platform == SourcePlatform.BILIBILI) {
            logger.event(taskId, "DOWNLOAD", "MEDIA_REQUEST_PROFILE_SELECTED", JSONObject().apply {
                put("profile", "bilibili-media-v1")
                put("user_agent_profile", "desktop_edge_124")
                put("referer_host", "m.bilibili.com")
                put("origin_present", true)
                put("cdn_cookie_sent", false)
            })
        }
        val source = File(folder, "${prefix}_source.mp4")
        val videoTrack = File(folder, "${prefix}_video.mp4")
        val audioTrack = File(folder, "${prefix}_audio.m4a")
        val merged = File(folder, finalDisplayName)

        if (spec.result.platform == SourcePlatform.BILIBILI) {
            return prepareBilibiliVideoFiles(
                taskId = taskId,
                variant = variant,
                mode = mode,
                audioUrls = audioUrls,
                referer = spec.result.referer,
                requestProfile = requestProfile,
                source = source,
                videoTrack = videoTrack,
                audioTrack = audioTrack,
                merged = merged,
                finalDisplayName = finalDisplayName,
                progress = progress,
            )
        }

        progress("准备下载原始视频", 0, true)
        download(
            taskId = taskId,
            urls = variant.urls,
            target = source,
            requestProfile = requestProfile,
            label = "视频",
            referer = spec.result.referer,
            fallbackTotalBytes = variant.size.takeIf {
                it > 0L && variant.sizeSource != "estimated"
            } ?: -1L,
            progress = progress,
        )
        progress("分析音视频轨道", 0, true)
        val sourceProbe = MediaTrackProcessor.probe(source)
        if (spec.result.platform == SourcePlatform.BILIBILI) {
            check(sourceProbe.size == 1 && sourceProbe.single()["mime"] == "video/avc") {
                "B站视频轨格式与解析结果不符，未发布文件"
            }
            check(mode == DownloadMode.VIDEO_ONLY || audioUrls.isNotEmpty()) {
                "B站缺少独立音轨，无法完成所选下载模式"
            }
        }
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
                sourceDisplayName = finalDisplayName,
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
                validateBilibiliTracks = spec.result.platform == SourcePlatform.BILIBILI,
                requestProfile = requestProfile,
                progress = progress,
            )
            VideoAudioSource.MISSING -> {
                if (mode == DownloadMode.AUDIO_ONLY) {
                    error("视频文件没有音频轨，也没有可用的独立音频地址")
                }
                logger.event(taskId, "MEDIA_PROCESS", "SILENT_VIDEO_PRESERVED", JSONObject().apply {
                    put("requested_mode", mode.wireValue)
                })
                listOf(source to finalDisplayName)
            }
        }
        return files
    }

    private suspend fun prepareBilibiliVideoFiles(
        taskId: String,
        variant: MediaVariant,
        mode: DownloadMode,
        audioUrls: List<String>,
        referer: String,
        requestProfile: MediaRequestProfile,
        source: File,
        videoTrack: File,
        audioTrack: File,
        merged: File,
        finalDisplayName: String,
        progress: DownloadProgress,
    ): List<Pair<File, String>> {
        val trustedVideoSize = variant.size.takeIf {
            it > 0L && variant.sizeSource != "estimated"
        } ?: -1L
        if (mode == DownloadMode.VIDEO_ONLY) {
            progress("准备下载原始视频", 0, true)
            download(
                taskId, variant.urls, source, "视频", referer, trustedVideoSize,
                requestProfile, progress, probeForAcceleration = true,
            )
            validateBilibiliTrack(source, "video/avc", "视频")
            return listOf(source to videoTrack.name)
        }
        check(audioUrls.isNotEmpty()) { "B站缺少独立音轨，无法完成所选下载模式" }
        if (mode == DownloadMode.AUDIO_ONLY) {
            progress("准备下载独立音频轨", 0, true)
            download(taskId, audioUrls, audioTrack, "独立音频", referer,
                requestProfile = requestProfile, progress = progress, probeForAcceleration = true)
            validateBilibiliTrack(audioTrack, "audio/mp4a-latm", "音频")
            return listOf(audioTrack to audioTrack.name)
        }

        val progressMutex = Mutex()
        trackProgressRegistry.start(
            taskId = taskId,
            videoTotalBytes = trustedVideoSize.coerceAtLeast(0L),
            includeVideo = true,
            includeAudio = true,
        )
        logger.event(taskId, "DOWNLOAD", "PARALLEL_TRACK_DOWNLOAD_STARTED", JSONObject().apply {
            put("video_candidates", variant.urls.size)
            put("audio_candidates", audioUrls.size)
            put("range_parts_per_track", ACCELERATED_PART_COUNT)
        })
        try {
            coroutineScope {
                val video = async {
                    download(
                        taskId = taskId,
                        urls = variant.urls,
                        target = source,
                        label = "视频轨",
                        referer = referer,
                        fallbackTotalBytes = trustedVideoSize,
                        requestProfile = requestProfile,
                        progress = NO_OP_DOWNLOAD_PROGRESS,
                        probeForAcceleration = true,
                        transferProgress = { downloaded, total, speed ->
                            progressMutex.withLock {
                                val current = trackProgressRegistry.update(
                                    taskId, DownloadTrackKind.VIDEO, downloaded, total, speed,
                                )
                                progress("正在并行下载B站音视频轨", combinedTrackProgress(current), true)
                            }
                        },
                    )
                }
                val audio = async {
                    download(
                        taskId = taskId,
                        urls = audioUrls,
                        target = audioTrack,
                        label = "音频轨",
                        referer = referer,
                        requestProfile = requestProfile,
                        progress = NO_OP_DOWNLOAD_PROGRESS,
                        probeForAcceleration = true,
                        transferProgress = { downloaded, total, speed ->
                            progressMutex.withLock {
                                val current = trackProgressRegistry.update(
                                    taskId, DownloadTrackKind.AUDIO, downloaded, total, speed,
                                )
                                progress("正在并行下载B站音视频轨", combinedTrackProgress(current), true)
                            }
                        },
                    )
                }
                listOf(video, audio).awaitAll()
            }
        } finally {
            trackProgressRegistry.clear(taskId)
        }

        progress("验证B站音视频轨", 0, true)
        validateBilibiliTrack(source, "video/avc", "视频")
        validateBilibiliTrack(audioTrack, "audio/mp4a-latm", "音频")
        return when (mode) {
            DownloadMode.MERGE_KEEP,
            DownloadMode.MP4_ONLY,
            -> {
                progress("无损合并音视频", 0, true)
                logger.event(taskId, "MEDIA_PROCESS", "MUX_STARTED")
                MediaTrackProcessor.mux(source, audioTrack, merged)
                val mergedProbe = MediaTrackProcessor.probe(merged)
                check(mergedProbe.map { it["mime"] }.toSet() == setOf("video/avc", "audio/mp4a-latm")) {
                    "B站合并产物缺少音视频轨，未发布文件"
                }
                logger.saveMediaProbe(taskId, mergedProbe.toString())
                if (mode == DownloadMode.MP4_ONLY) {
                    listOf(merged to merged.name)
                } else {
                    listOf(source to videoTrack.name, audioTrack to audioTrack.name, merged to merged.name)
                }
            }
            DownloadMode.TRACKS -> listOf(source to videoTrack.name, audioTrack to audioTrack.name)
            DownloadMode.VIDEO_ONLY, DownloadMode.AUDIO_ONLY -> error("不可达的B站下载模式")
        }
    }

    private fun validateBilibiliTrack(file: File, expectedMime: String, label: String) {
        val tracks = MediaTrackProcessor.probe(file)
        check(tracks.size == 1 && tracks.single()["mime"] == expectedMime) {
            "B站${label}轨格式与解析结果不符，未发布文件"
        }
    }

    private suspend fun processEmbeddedAudio(
        source: File,
        videoTrack: File,
        audioTrack: File,
        sourceDisplayName: String,
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
                    source to sourceDisplayName,
                )
            }
            DownloadMode.MP4_ONLY -> listOf(source to sourceDisplayName)
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
        validateBilibiliTracks: Boolean,
        requestProfile: MediaRequestProfile,
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
            requestProfile = requestProfile,
            label = "独立音频",
            referer = referer,
            progress = progress,
        )
        if (validateBilibiliTracks) {
            val audioProbe = MediaTrackProcessor.probe(audioTrack)
            check(audioProbe.size == 1 && audioProbe.single()["mime"] == "audio/mp4a-latm") {
                "B站音频轨格式与解析结果不符，未发布文件"
            }
        }
        return when (mode) {
            DownloadMode.MERGE_KEEP,
            DownloadMode.MP4_ONLY,
            -> {
                progress("无损合并音视频", 0, true)
                logger.event(taskId, "MEDIA_PROCESS", "MUX_STARTED")
                MediaTrackProcessor.mux(source, audioTrack, merged)
                val mergedProbe = MediaTrackProcessor.probe(merged)
                if (validateBilibiliTracks) {
                    check(mergedProbe.map { it["mime"] }.toSet() == setOf("video/avc", "audio/mp4a-latm")) {
                        "B站合并产物缺少音视频轨，未发布文件"
                    }
                }
                logger.saveMediaProbe(taskId, mergedProbe.toString())
                if (mode == DownloadMode.MP4_ONLY) {
                    listOf(merged to merged.name)
                } else {
                    listOf(
                        source to videoTrack.name,
                        audioTrack to audioTrack.name,
                        merged to merged.name,
                    )
                }
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
        persistIntermediateOutputs: Boolean,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit,
        progress: DownloadProgress,
    ): List<TaskOutput> {
        progress("保存到公共下载目录", 0, true)
        val published = mutableListOf<TaskOutput>()
        files.forEach { (file, name) ->
            published += PublicStorage.publish(context, file, spec, name)
            recordPublishedOutputs(
                taskId,
                published,
                persistIntermediateOutputs,
                onPublishedOutputs,
            )
        }
        return published
    }

    private suspend fun recordPublishedOutputs(
        taskId: String,
        outputs: List<TaskOutput>,
        persistIntermediateOutputs: Boolean,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit,
    ) {
        val snapshot = outputs.toList()
        if (persistIntermediateOutputs) repository.replaceOutputs(taskId, snapshot)
        onPublishedOutputs(snapshot)
    }

    private suspend fun download(
        taskId: String,
        urls: List<String>,
        target: File,
        label: String,
        referer: String,
        fallbackTotalBytes: Long = -1L,
        requestProfile: MediaRequestProfile = MediaRequestProfile.standard(referer),
        progress: DownloadProgress,
        probeForAcceleration: Boolean = false,
        transferProgress: (suspend (downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long) -> Unit)? = null,
    ) {
        suspend fun report(downloaded: Long, total: Long, speed: Long) {
            adaptiveDownloadController.recordTransfer(
                taskId = taskId,
                channel = target.name,
                downloadedBytes = downloaded,
                totalBytes = total,
                bytesPerSecond = speed,
            )
            progress(
                formatDownloadStatus(label, downloaded, total, speed),
                downloadFileProgress(downloaded, total),
                true,
            )
            transferProgress?.invoke(downloaded, total, speed)
        }
        try {
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
        val orderedUrls = if (probeForAcceleration || shouldAccelerateDownload(fallbackTotalBytes)) {
            val selection = acceleratedDownloader.selectCandidate(
                addresses = securedUrls,
                referer = referer,
                fallbackTotalBytes = fallbackTotalBytes,
                requestProfile = requestProfile,
            )
            if (selection != null) {
                logger.event(taskId, "DOWNLOAD", "CDN_SELECTED", JSONObject().apply {
                    put("cdn_index", securedUrls.indexOf(selection.address))
                    put("cdn_host", URL(selection.address).host)
                    put("probe_bytes_per_second", selection.bytesPerSecond)
                    put("range_supported", selection.rangeSupported)
                    put("total_bytes", selection.totalBytes)
                })
                if (selection.rangeSupported && shouldAccelerateDownload(selection.totalBytes)) {
                    try {
                        report(0L, selection.totalBytes, 0L)
                        logger.event(taskId, "DOWNLOAD", "RANGE_DOWNLOAD_STARTED", JSONObject().apply {
                            put("parts", ACCELERATED_PART_COUNT)
                            put("cdn_host", URL(selection.address).host)
                            put("total_bytes", selection.totalBytes)
                        })
                        acceleratedDownloader.downloadRanges(
                            selection = selection,
                            target = target,
                            referer = referer,
                            partCount = ACCELERATED_PART_COUNT,
                            requestProfile = requestProfile,
                        ) { downloaded, total, bytesPerSecond ->
                            report(downloaded, total, bytesPerSecond)
                        }
                        logger.event(taskId, "DOWNLOAD", "FILE_DOWNLOADED", JSONObject().apply {
                            put("name", target.name)
                            put("bytes", target.length())
                            put("cdn_index", securedUrls.indexOf(selection.address))
                            put("cdn_host", URL(selection.address).host)
                            put("transfer_mode", "range_$ACCELERATED_PART_COUNT")
                        })
                        return
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        target.delete()
                        logger.event(taskId, "DOWNLOAD", "RANGE_DOWNLOAD_FALLBACK", JSONObject().apply {
                            put("cdn_host", URL(selection.address).host)
                            put("message", error.message ?: error.javaClass.simpleName)
                        })
                    }
                }
                listOf(selection.address) + securedUrls.filterNot { it == selection.address }
            } else {
                securedUrls
            }
        } else {
            securedUrls
        }
        orderedUrls.forEachIndexed { addressIndex, address ->
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    val requestStartedAt = System.nanoTime()
                    val connection = requestProfile.open(address, 20_000, 120_000)
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) error("CDN HTTP $status")
                        val total = connection.contentLengthLong.takeIf { it > 0L }
                            ?: fallbackTotalBytes
                        report(0L, total, 0L)
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
                                    val bytesPerSecond = calculateSampledTransferSpeed(
                                        downloadedBytes = downloaded,
                                        lastReportedBytes = lastReportedBytes,
                                        intervalElapsedNanos = elapsed,
                                        requestElapsedNanos = now - requestStartedAt,
                                    )
                                    report(downloaded, total, bytesPerSecond)
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
                        put("cdn_index", securedUrls.indexOf(address).takeIf { it >= 0 } ?: addressIndex)
                        put("cdn_host", URL(address).host)
                        put("transfer_mode", "single")
                    })
                    return
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    lastError = error
                    target.delete()
                    logger.event(taskId, "DOWNLOAD", "CDN_ATTEMPT_FAILED", JSONObject().apply {
                        put("cdn_index", securedUrls.indexOf(address).takeIf { it >= 0 } ?: addressIndex)
                        put("attempt", attempt + 1)
                        put("cdn_host", URL(address).host)
                        put("media_label", label)
                        put("message", error.message ?: error.javaClass.simpleName)
                    })
                }
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载地址")
        } finally {
            adaptiveDownloadController.finishTransfer(taskId, target.name)
        }
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
        private val NO_OP_DOWNLOAD_PROGRESS: DownloadProgress = { _, _, _ -> }
        private const val ACCELERATED_PART_COUNT = 4
        private const val PROGRESS_REPORT_INTERVAL_NANOS = 750_000_000L
    }
}
