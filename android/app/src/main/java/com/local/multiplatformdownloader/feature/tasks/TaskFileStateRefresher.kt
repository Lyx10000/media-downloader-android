package com.local.multiplatformdownloader.feature.tasks

import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.storage.StorageInspector

import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class TaskFileStateRefresher @Inject constructor(
    private val repository: DownloadTaskRepository,
    private val inspector: StorageInspector,
    private val logger: DiagnosticLogger,
) {
    suspend fun refresh(records: List<TaskRecord>) {
        records.forEach { task ->
            if (task.status == TaskStatus.DELETING) return@forEach
            val state = inspector.inspect(task, repository.getSpec(task.id))
            if (state != task.fileState) {
                repository.updateFileState(task.id, state)
                logger.event(task.id, "STORAGE", "FILE_STATE_CHANGED", JSONObject().apply {
                    put("from", task.fileState.wireValue)
                    put("to", state.wireValue)
                    put("outputs", task.outputs.size)
                })
            }
        }
    }
}
