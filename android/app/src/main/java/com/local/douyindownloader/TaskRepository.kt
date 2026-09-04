package com.local.douyindownloader

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

interface DownloadTaskRepository {
    suspend fun insert(spec: TaskSpec)
    suspend fun getSpec(taskId: String): TaskSpec?
    suspend fun update(
        taskId: String,
        status: TaskStatus,
        stage: String,
        progress: Int,
        error: String = "",
    )
    suspend fun replaceSpec(taskId: String, spec: TaskSpec)
    suspend fun updateFileState(taskId: String, fileState: FileState)
    suspend fun clearOutputs(taskId: String, fileState: FileState = FileState.UNKNOWN)
    suspend fun replaceOutputs(taskId: String, outputs: List<TaskOutput>)
    suspend fun setDeleteFailed(taskId: String, progress: Int, error: String)
    suspend fun delete(taskId: String)
    suspend fun complete(taskId: String, outputs: List<TaskOutput>, stage: String = "已完成")
    suspend fun list(): List<TaskRecord>
    fun observe(): Flow<List<TaskRecord>>
    suspend fun get(taskId: String): TaskRecord?
}

@Singleton
class RoomDownloadTaskRepository @Inject constructor(
    private val dao: TaskDao,
) : DownloadTaskRepository {
    override suspend fun insert(spec: TaskSpec) {
        val title = spec.result.document?.title?.take(40).orEmpty().ifBlank {
            spec.result.author.ifBlank {
                spec.result.description.take(30).ifBlank { spec.result.contentId }
            }
        }
        dao.insert(
            TaskEntity(
                id = spec.taskId,
                createdAt = spec.createdAt,
                status = TaskStatus.QUEUED.wireValue,
                stage = "等待下载",
                progress = 0,
                title = title,
                spec = spec.toJson(),
                outputs = "[]",
                error = "",
                fileStatus = FileState.UNKNOWN.wireValue,
            ),
        )
    }

    override suspend fun getSpec(taskId: String): TaskSpec? = dao.getSpec(taskId)?.let { json ->
        runCatching { TaskSpec.fromJson(json) }.getOrNull()
    }

    override suspend fun update(
        taskId: String,
        status: TaskStatus,
        stage: String,
        progress: Int,
        error: String,
    ) {
        val safeProgress = progress.coerceIn(0, 100)
        val safeError = Redactor.sanitize(error)
        if (status == TaskStatus.DELETING) {
            dao.updateIncludingDeleting(taskId, status.wireValue, stage, safeProgress, safeError)
        } else {
            dao.updateUnlessDeleting(taskId, status.wireValue, stage, safeProgress, safeError)
        }
    }

    override suspend fun replaceSpec(taskId: String, spec: TaskSpec) {
        dao.replaceSpec(taskId, spec.toJson())
    }

    override suspend fun updateFileState(taskId: String, fileState: FileState) {
        dao.updateFileState(taskId, fileState.wireValue)
    }

    override suspend fun clearOutputs(taskId: String, fileState: FileState) {
        dao.clearOutputs(taskId, fileState.wireValue)
    }

    override suspend fun replaceOutputs(taskId: String, outputs: List<TaskOutput>) {
        dao.replaceOutputs(
            taskId,
            outputs.toJson(),
            if (outputs.isEmpty()) FileState.UNKNOWN.wireValue else FileState.AVAILABLE.wireValue,
        )
    }

    override suspend fun setDeleteFailed(taskId: String, progress: Int, error: String) {
        dao.setDeleteFailed(taskId, progress.coerceIn(0, 100), Redactor.sanitize(error))
    }

    override suspend fun delete(taskId: String) = dao.delete(taskId)

    override suspend fun complete(taskId: String, outputs: List<TaskOutput>, stage: String) {
        dao.complete(taskId, outputs.toJson(), stage)
    }

    override suspend fun list(): List<TaskRecord> = dao.list().map(TaskEntity::toRecord)

    override fun observe(): Flow<List<TaskRecord>> = dao.observe().map { entities ->
        entities.map(TaskEntity::toRecord)
    }

    override suspend fun get(taskId: String): TaskRecord? = dao.get(taskId)?.toRecord()
}

internal fun TaskEntity.toRecord(): TaskRecord {
    val storedSpec = runCatching { TaskSpec.fromJson(spec) }.getOrNull()
    val parsedResult = storedSpec?.result
    val sourcePlatform = parsedResult?.platform ?: SourcePlatform.DOUYIN
    val outputJson = runCatching { JSONArray(outputs) }.getOrNull()
    val outputRecords = if (outputJson == null) {
        emptyList()
    } else {
        (0 until outputJson.length()).mapNotNull { index ->
            TaskOutput.fromJson(outputJson.opt(index))
        }
    }
    return TaskRecord(
        id = id,
        createdAt = createdAt,
        status = if (outputJson == null) TaskStatus.FAILED else TaskStatus.fromWire(status),
        stage = stage,
        progress = progress,
        title = title,
        platform = sourcePlatform,
        outputs = outputRecords,
        error = if (outputJson == null) error.ifBlank { "任务输出记录损坏" } else error,
        fileState = if (outputJson == null) FileState.UNKNOWN else FileState.fromWire(fileStatus),
        author = parsedResult?.author.orEmpty(),
        authorAccountId = parsedResult?.authorAccountId.orEmpty(),
    )
}

private fun List<TaskOutput>.toJson(): String = JSONArray().apply {
    forEach { put(it.toJson()) }
}.toString()
