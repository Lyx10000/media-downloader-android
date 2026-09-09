package com.local.multiplatformdownloader.feature.download

import android.content.Context
import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.core.storage.PublicStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class OutputPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DownloadTaskRepository,
) {
    suspend fun publish(
        source: File,
        spec: TaskSpec,
        displayName: String,
        relativeDirectory: String = "",
    ): TaskOutput = PublicStorage.publish(
        context = context,
        source = source,
        spec = spec,
        displayName = displayName,
        relativeDirectory = relativeDirectory,
    )

    suspend fun publishAll(
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
            published += publish(file, spec, name)
            recordPublishedOutputs(
                taskId,
                published,
                persistIntermediateOutputs,
                onPublishedOutputs,
            )
        }
        return published
    }

    suspend fun recordPublishedOutputs(
        taskId: String,
        outputs: List<TaskOutput>,
        persistIntermediateOutputs: Boolean,
        onPublishedOutputs: suspend (List<TaskOutput>) -> Unit,
    ) {
        val snapshot = outputs.toList()
        if (persistIntermediateOutputs) repository.replaceOutputs(taskId, snapshot)
        onPublishedOutputs(snapshot)
    }
}
