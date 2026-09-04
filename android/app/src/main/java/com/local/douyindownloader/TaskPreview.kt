package com.local.douyindownloader

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class TaskPreviewKind {
    IMAGE,
    VIDEO,
    AUDIO,
}

internal enum class VideoAudioMode {
    EMBEDDED,
    SEPARATE,
    SILENT,
}

internal data class MediaTrackInfo(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

internal data class PreviewCandidate(
    val output: TaskOutput,
    val uri: Uri,
    val mimeType: String,
    val tracks: MediaTrackInfo? = null,
)

internal data class PreviewSelection(
    val output: TaskOutput,
    val kind: TaskPreviewKind,
    val matchingOutputCount: Int,
    val imageOutputs: List<TaskOutput> = emptyList(),
    val audioOutput: TaskOutput? = null,
    val videoAudioMode: VideoAudioMode? = null,
)

internal data class TaskPreviewImage(
    val output: TaskOutput,
    val uri: Uri,
    val mimeType: String,
)

internal data class TaskPreviewMedia(
    val output: TaskOutput,
    val uri: Uri,
    val mimeType: String,
    val kind: TaskPreviewKind,
    val matchingOutputCount: Int,
    val images: List<TaskPreviewImage> = emptyList(),
    val audioOutput: TaskOutput? = null,
    val audioUri: Uri? = null,
    val videoAudioMode: VideoAudioMode? = null,
)

internal fun selectTaskPreview(candidates: List<PreviewCandidate>): TaskPreviewMedia? {
    val selection = selectPreviewOutput(
        outputs = candidates.map(PreviewCandidate::output),
        mimeTypeFor = { output -> candidates.first { it.output == output }.mimeType },
        tracksFor = { output -> candidates.first { it.output == output }.tracks },
    ) ?: return null
    val selected = candidates.first { it.output == selection.output }
    val audio = selection.audioOutput?.let { audioOutput ->
        candidates.firstOrNull { it.output == audioOutput }
    }
    val images = selection.imageOutputs.mapNotNull { imageOutput ->
        candidates.firstOrNull { it.output == imageOutput }?.let { image ->
            TaskPreviewImage(image.output, image.uri, image.mimeType)
        }
    }
    return selected.toMedia(
        kind = selection.kind,
        matchingOutputCount = selection.matchingOutputCount,
        images = images,
        audio = audio,
        videoAudioMode = selection.videoAudioMode,
    )
}

internal fun selectTaskPreviews(candidates: List<PreviewCandidate>): List<TaskPreviewMedia> {
    val groups = documentPreviewGroups(candidates.map(PreviewCandidate::output))
    val documentMedia = candidates.filter { it.output.relativePath.startsWith("media/") }
    if (documentMedia.isEmpty()) return listOfNotNull(selectTaskPreview(candidates))
    val images = candidates.filter { it.output in groups.images }
    val videos = candidates.filter { it.output in groups.videos }
    return buildList {
        selectTaskPreview(images)?.let(::add)
        videos.mapNotNullTo(this) { video -> selectTaskPreview(listOf(video)) }
        if (isEmpty()) selectTaskPreview(documentMedia)?.let(::add)
    }
}

internal data class DocumentPreviewGroups(
    val images: List<TaskOutput>,
    val videos: List<TaskOutput>,
)

internal fun documentPreviewGroups(outputs: List<TaskOutput>): DocumentPreviewGroups {
    val media = outputs.filter { it.relativePath.startsWith("media/") }
    return DocumentPreviewGroups(
        images = media.filter {
            mediaMimeType(it.displayName, it.mimeType).startsWith("image/") &&
                "_cover." !in it.displayName
        },
        videos = media.filter { mediaMimeType(it.displayName, it.mimeType).startsWith("video/") },
    )
}

internal fun selectPreviewOutput(
    outputs: List<TaskOutput>,
    mimeTypeFor: (TaskOutput) -> String = { output ->
        mediaMimeType(output.displayName, output.mimeType)
    },
    tracksFor: (TaskOutput) -> MediaTrackInfo? = { null },
): PreviewSelection? {
    val images = outputs.filter { previewKind(mimeTypeFor(it)) == TaskPreviewKind.IMAGE }
    images.firstOrNull()?.let { image ->
        return PreviewSelection(
            output = image,
            kind = TaskPreviewKind.IMAGE,
            matchingOutputCount = images.size,
            imageOutputs = images,
        )
    }

    val videos = outputs.filter { previewKind(mimeTypeFor(it)) == TaskPreviewKind.VIDEO }
    val audio = outputs.firstOrNull { previewKind(mimeTypeFor(it)) == TaskPreviewKind.AUDIO }
    if (videos.isNotEmpty()) {
        val embedded = videos.firstOrNull { tracksFor(it)?.let { tracks ->
            tracks.hasVideo && tracks.hasAudio
        } == true } ?: videos.firstOrNull { output ->
            tracksFor(output) == null && !looksLikeSeparateVideoTrack(output.displayName)
        }
        if (embedded != null) {
            return PreviewSelection(
                output = embedded,
                kind = TaskPreviewKind.VIDEO,
                matchingOutputCount = videos.size,
                videoAudioMode = VideoAudioMode.EMBEDDED,
            )
        }

        val video = videos.firstOrNull { tracksFor(it)?.hasVideo != false } ?: videos.first()
        return PreviewSelection(
            output = video,
            kind = TaskPreviewKind.VIDEO,
            matchingOutputCount = videos.size,
            audioOutput = audio,
            videoAudioMode = if (audio == null) VideoAudioMode.SILENT else VideoAudioMode.SEPARATE,
        )
    }

    return audio?.let {
        PreviewSelection(
            output = it,
            kind = TaskPreviewKind.AUDIO,
            matchingOutputCount = outputs.count { output ->
                previewKind(mimeTypeFor(output)) == TaskPreviewKind.AUDIO
            },
        )
    }
}

private fun PreviewCandidate.toMedia(
    kind: TaskPreviewKind,
    matchingOutputCount: Int,
    images: List<TaskPreviewImage> = emptyList(),
    audio: PreviewCandidate? = null,
    videoAudioMode: VideoAudioMode? = null,
) = TaskPreviewMedia(
    output = output,
    uri = uri,
    mimeType = mimeType,
    kind = kind,
    matchingOutputCount = matchingOutputCount,
    images = images,
    audioOutput = audio?.output,
    audioUri = audio?.uri,
    videoAudioMode = videoAudioMode,
)

private fun looksLikeSeparateVideoTrack(displayName: String): Boolean {
    val stem = displayName.substringBeforeLast('.', displayName).lowercase()
    return stem.endsWith("_video") || stem.endsWith("-video")
}

@Singleton
class MediaTrackInspector @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val resolver = context.contentResolver

    internal fun inspect(uri: Uri): MediaTrackInfo? {
        val extractor = MediaExtractor()
        return try {
            when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> {
                    val path = uri.path ?: return null
                    extractor.setDataSource(path)
                }
                ContentResolver.SCHEME_CONTENT -> {
                    val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: return null
                    descriptor.use { afd ->
                        if (afd.declaredLength >= 0L) {
                            extractor.setDataSource(
                                afd.fileDescriptor,
                                afd.startOffset,
                                afd.declaredLength,
                            )
                        } else {
                            extractor.setDataSource(afd.fileDescriptor)
                        }
                        return inspectTracks(extractor)
                    }
                }
                else -> return null
            }
            inspectTracks(extractor)
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun inspectTracks(extractor: MediaExtractor): MediaTrackInfo {
        var hasVideo = false
        var hasAudio = false
        repeat(extractor.trackCount) { index ->
            val mime = extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                .orEmpty()
            hasVideo = hasVideo || mime.startsWith("video/")
            hasAudio = hasAudio || mime.startsWith("audio/")
        }
        return MediaTrackInfo(hasVideo = hasVideo, hasAudio = hasAudio)
    }
}

@Singleton
class TaskPreviewResolver @Inject constructor(
    @ApplicationContext context: Context,
    private val trackInspector: MediaTrackInspector,
    private val logger: DiagnosticLogger,
) {
    private val resolver = context.contentResolver

    internal suspend fun resolve(taskId: String, outputs: List<TaskOutput>): List<TaskPreviewMedia> =
        withContext(Dispatchers.IO) {
            val candidates = outputs.mapNotNull { output ->
                val uri = runCatching { Uri.parse(output.uri) }.getOrNull() ?: return@mapNotNull null
                if (!isPreviewOutputReadable(resolver, uri)) return@mapNotNull null
                val providerType = runCatching { resolver.getType(uri) }.getOrNull()
                val mimeType = mediaMimeType(
                    output.displayName,
                    output.mimeType.ifBlank { providerType.orEmpty() },
                )
                val tracks = if (previewKind(mimeType) == TaskPreviewKind.VIDEO) {
                    trackInspector.inspect(uri)
                } else {
                    null
                }
                PreviewCandidate(output, uri, mimeType, tracks)
            }
            selectTaskPreviews(candidates).also { media ->
                runCatching {
                    logger.event(taskId, "PREVIEW_MEDIA", "SOURCE_RESOLVED", JSONObject().apply {
                        put("kind", media.joinToString(",") { it.kind.name.lowercase() }.ifBlank { "none" })
                        put("preview_groups", media.size)
                        put("readable_outputs", candidates.size)
                        put("image_outputs", candidates.count {
                            previewKind(it.mimeType) == TaskPreviewKind.IMAGE
                        })
                        put("video_outputs", candidates.count {
                            previewKind(it.mimeType) == TaskPreviewKind.VIDEO
                        })
                    })
                }
            }
        }
}

private fun previewKind(mimeType: String): TaskPreviewKind? = when (mediaCategory(mimeType)) {
    "image" -> TaskPreviewKind.IMAGE
    "video" -> TaskPreviewKind.VIDEO
    "audio" -> TaskPreviewKind.AUDIO
    else -> null
}

private fun isPreviewOutputReadable(resolver: ContentResolver, uri: Uri): Boolean = runCatching {
    when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.isFile == true
        ContentResolver.SCHEME_CONTENT ->
            resolver.openFileDescriptor(uri, "r")?.use { it.fileDescriptor.valid() } == true
        else -> false
    }
}.getOrDefault(false)
