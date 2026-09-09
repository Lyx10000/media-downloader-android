package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.download.DownloadTrackKind
import com.local.multiplatformdownloader.core.download.MediaTrackProcessor
import com.local.multiplatformdownloader.core.download.TrackDownloadProgressRegistry
import com.local.multiplatformdownloader.core.download.VideoAudioSource
import com.local.multiplatformdownloader.core.download.chooseVideoAudioSource
import com.local.multiplatformdownloader.core.download.combinedTrackProgress
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.core.network.MediaRequestProfile
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

@Singleton
internal class VideoDownloadExecutor @Inject constructor(
    private val logger: DiagnosticLogger,
    private val trackProgressRegistry: TrackDownloadProgressRegistry,
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
    ): List<TaskOutput> {
        val variant = spec.result.variants.getOrNull(spec.variantIndex)
            ?: error("没有可下载的视频档位")
        val files = prepareFiles(
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
        return outputPublisher.publishAll(
            taskId,
            spec,
            files,
            persistIntermediateOutputs,
            onPublishedOutputs,
            progress,
        )
    }

    internal suspend fun prepareFiles(
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
        mediaTransferClient.download(
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
            mediaTransferClient.download(
                taskId, variant.urls, source, "视频", referer, trustedVideoSize,
                requestProfile, progress, probeForAcceleration = true,
            )
            validateBilibiliTrack(source, "video/avc", "视频")
            return listOf(source to videoTrack.name)
        }
        check(audioUrls.isNotEmpty()) { "B站缺少独立音轨，无法完成所选下载模式" }
        if (mode == DownloadMode.AUDIO_ONLY) {
            progress("准备下载独立音频轨", 0, true)
            mediaTransferClient.download(taskId, audioUrls, audioTrack, "独立音频", referer,
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
                    mediaTransferClient.download(
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
                    mediaTransferClient.download(
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
        mediaTransferClient.download(
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


    private companion object {
        val NO_OP_DOWNLOAD_PROGRESS: DownloadProgress = { _, _, _ -> }
        const val ACCELERATED_PART_COUNT = 4
    }
}

