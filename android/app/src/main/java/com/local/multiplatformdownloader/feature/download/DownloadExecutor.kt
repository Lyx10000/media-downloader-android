package com.local.multiplatformdownloader.feature.download

import com.local.multiplatformdownloader.core.download.AdaptiveDownloadController
import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.model.MediaKind
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.platform.bilibili.BilibiliDeferredResolver

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadExecutionResult(
    val outputs: List<TaskOutput>,
    val warningCount: Int = 0,
)

@Singleton
class DownloadExecutor @Inject internal constructor(
    private val bilibiliDeferredResolver: BilibiliDeferredResolver,
    private val adaptiveDownloadController: AdaptiveDownloadController,
    private val documentDownloadExecutor: DocumentDownloadExecutor,
    private val imageDownloadExecutor: ImageDownloadExecutor,
    private val videoDownloadExecutor: VideoDownloadExecutor,
    private val attachmentDownloadExecutor: AttachmentDownloadExecutor,
) {

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
            attachmentDownloadExecutor.execute(
                taskId,
                spec,
                folder,
                persistIntermediateOutputs,
                onPublishedOutputs,
                progress,
            )
        } else when (spec.result.kind) {
            MediaKind.IMAGE -> imageDownloadExecutor.execute(
                taskId,
                spec,
                folder,
                persistIntermediateOutputs,
                onPublishedOutputs,
                progress,
            )
            MediaKind.VIDEO -> DownloadExecutionResult(
                videoDownloadExecutor.execute(
                    taskId,
                    readySpec,
                    folder,
                    persistIntermediateOutputs,
                    onPublishedOutputs,
                    progress,
                ),
            )
            MediaKind.DOCUMENT -> documentDownloadExecutor.execute(
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
}
