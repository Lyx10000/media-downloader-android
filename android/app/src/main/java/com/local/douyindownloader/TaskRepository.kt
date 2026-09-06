package com.local.douyindownloader

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    suspend fun updateAuthorKey(taskId: String, authorKey: String)
    suspend fun updateFileState(taskId: String, fileState: FileState)
    suspend fun clearOutputs(taskId: String, fileState: FileState = FileState.UNKNOWN)
    suspend fun replaceOutputs(taskId: String, outputs: List<TaskOutput>)
    suspend fun setDeleteFailed(taskId: String, progress: Int, error: String)
    suspend fun delete(taskId: String)
    suspend fun complete(taskId: String, outputs: List<TaskOutput>, stage: String = "已完成")
    suspend fun list(): List<TaskRecord>
    suspend fun listAll(): List<TaskRecord>
    fun observe(): Flow<List<TaskRecord>>
    fun observeAll(): Flow<List<TaskRecord>>
    suspend fun listForAuthor(authorKey: String): List<TaskRecord>
    suspend fun get(taskId: String): TaskRecord?
}

@Singleton
class RoomDownloadTaskRepository @Inject constructor(
    private val dao: TaskDao,
) : DownloadTaskRepository {
    private val payloadMutex = Mutex()
    private val payloadCache = mutableMapOf<String, TaskRecordPayload>()

    override suspend fun insert(spec: TaskSpec) {
        val specJson = spec.toJson()
        val title = taskPreferredTitle(spec.result)
        dao.insert(
            TaskEntity(
                id = spec.taskId,
                createdAt = spec.createdAt,
                status = TaskStatus.QUEUED.wireValue,
                stage = "等待下载",
                progress = 0,
                title = title,
                spec = specJson,
                outputs = "[]",
                error = "",
                fileStatus = FileState.UNKNOWN.wireValue,
                authorKey = spec.authorKey,
                batchId = spec.batchId,
                creatorChild = spec.creatorChild,
            ),
        )
        cachePayload(
            spec.taskId,
            TaskRecordPayload(spec.toRecordSpec(), emptyList(), outputsValid = true),
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
        val json = spec.toJson()
        payloadMutex.withLock {
            dao.replaceSpec(taskId, json)
            payloadCache[taskId]?.let { payloadCache[taskId] = it.copy(spec = spec.toRecordSpec()) }
        }
    }

    override suspend fun updateAuthorKey(taskId: String, authorKey: String) {
        dao.updateAuthorKey(taskId, authorKey)
    }

    override suspend fun updateFileState(taskId: String, fileState: FileState) {
        dao.updateFileState(taskId, fileState.wireValue)
    }

    override suspend fun clearOutputs(taskId: String, fileState: FileState) {
        payloadMutex.withLock {
            dao.clearOutputs(taskId, fileState.wireValue)
            payloadCache[taskId]?.let {
                payloadCache[taskId] = it.copy(outputs = emptyList(), outputsValid = true)
            }
        }
    }

    override suspend fun replaceOutputs(taskId: String, outputs: List<TaskOutput>) {
        val json = outputs.toJson()
        payloadMutex.withLock {
            dao.replaceOutputs(
                taskId,
                json,
                if (outputs.isEmpty()) FileState.UNKNOWN.wireValue else FileState.AVAILABLE.wireValue,
            )
            payloadCache[taskId]?.let {
                payloadCache[taskId] = it.copy(outputs = outputs, outputsValid = true)
            }
        }
    }

    override suspend fun setDeleteFailed(taskId: String, progress: Int, error: String) {
        dao.setDeleteFailed(taskId, progress.coerceIn(0, 100), Redactor.sanitize(error))
    }

    override suspend fun delete(taskId: String) {
        payloadMutex.withLock {
            dao.delete(taskId)
            payloadCache.remove(taskId)
        }
    }

    override suspend fun complete(taskId: String, outputs: List<TaskOutput>, stage: String) {
        val json = outputs.toJson()
        payloadMutex.withLock {
            dao.complete(taskId, json, stage)
            payloadCache[taskId]?.let {
                payloadCache[taskId] = it.copy(outputs = outputs, outputsValid = true)
            }
        }
    }

    override suspend fun list(): List<TaskRecord> = hydrate(dao.listIndex())

    override suspend fun listAll(): List<TaskRecord> = hydrate(dao.listAllIndex())

    override fun observe(): Flow<List<TaskRecord>> = dao.observeIndex().map { rows ->
        hydrate(rows)
    }

    override fun observeAll(): Flow<List<TaskRecord>> = dao.observeAllIndex().map { rows ->
        hydrate(rows)
    }

    override suspend fun listForAuthor(authorKey: String): List<TaskRecord> =
        hydrate(dao.listForAuthorIndex(authorKey))

    override suspend fun get(taskId: String): TaskRecord? = payloadMutex.withLock {
        dao.get(taskId)?.let { entity ->
            val payload = TaskPayloadRow(entity.spec, entity.outputs).toRecordPayload()
            payloadCache[entity.id] = payload
            entity.toIndexRow().toRecord(payload)
        }
    }

    private suspend fun hydrate(rows: List<TaskIndexRow>): List<TaskRecord> = buildList(rows.size) {
        rows.forEach { row ->
            payloadFor(row.id)?.let { add(row.toRecord(it)) }
        }
    }

    private suspend fun payloadFor(taskId: String): TaskRecordPayload? = payloadMutex.withLock {
        payloadCache[taskId] ?: dao.getPayload(taskId)?.toRecordPayload()?.also {
            payloadCache[taskId] = it
        }
    }

    private suspend fun cachePayload(taskId: String, payload: TaskRecordPayload) {
        payloadMutex.withLock { payloadCache[taskId] = payload }
    }

}

internal fun TaskEntity.toRecord(): TaskRecord {
    return toIndexRow().toRecord(TaskPayloadRow(spec, outputs).toRecordPayload())
}

private fun TaskEntity.toIndexRow() = TaskIndexRow(
    id = id,
    createdAt = createdAt,
    status = status,
    stage = stage,
    progress = progress,
    title = title,
    error = error,
    fileStatus = fileStatus,
    authorKey = authorKey,
    batchId = batchId,
    creatorChild = creatorChild,
)

private fun TaskIndexRow.toRecord(payload: TaskRecordPayload): TaskRecord {
    val spec = payload.spec
    return TaskRecord(
        id = id,
        contentId = spec.contentId,
        createdAt = createdAt,
        status = if (!payload.outputsValid) TaskStatus.FAILED else TaskStatus.fromWire(status),
        stage = stage,
        progress = progress,
        title = if (spec.bilibiliPage > 0) "P${spec.bilibiliPage} · " +
            taskDisplayTitle(title, spec.description).substringAfter(" · P${spec.bilibiliPage} ")
            else taskDisplayTitle(title, spec.description),
        platform = spec.platform,
        outputs = payload.outputs,
        error = if (!payload.outputsValid) error.ifBlank { "任务输出记录损坏" } else error,
        fileState = if (!payload.outputsValid) FileState.UNKNOWN else FileState.fromWire(fileStatus),
        author = spec.author,
        authorAccountId = spec.authorAccountId,
        authorKey = authorKey.ifBlank { spec.authorKey },
        batchId = batchId.ifBlank { spec.batchId },
        creatorChild = creatorChild || spec.creatorChild,
        questionArchiveId = spec.questionArchiveId,
        questionChild = spec.questionChild,
        storageMode = spec.storageMode,
        bilibiliPage = spec.bilibiliPage,
        bilibiliTitle = spec.bilibiliTitle,
    )
}

private data class TaskRecordPayload(
    val spec: TaskRecordSpec,
    val outputs: List<TaskOutput>,
    val outputsValid: Boolean,
)

private data class TaskRecordSpec(
    val contentId: String = "",
    val platform: SourcePlatform = SourcePlatform.DOUYIN,
    val kind: MediaKind? = null,
    val description: String = "",
    val author: String = "",
    val authorAccountId: String = "",
    val authorKey: String = "",
    val batchId: String = "",
    val creatorChild: Boolean = false,
    val questionArchiveId: String = "",
    val questionChild: Boolean = false,
    val storageMode: StorageMode = StorageMode.LEGACY,
    val bilibiliPage: Int = 0,
    val bilibiliTitle: String = "",
)

private fun TaskSpec.toRecordSpec() = TaskRecordSpec(
    contentId = result.contentId,
    platform = result.platform,
    kind = result.kind,
    description = result.description.take(60),
    author = result.author,
    authorAccountId = result.authorAccountId,
    authorKey = authorKey,
    batchId = batchId,
    creatorChild = creatorChild,
    questionArchiveId = questionArchiveId,
    questionChild = questionChild,
    storageMode = storageMode,
    bilibiliPage = result.bilibiliParts.firstOrNull { result.contentId.endsWith(":" + it.cid) }?.page ?: 0,
    bilibiliTitle = result.bilibiliTitle.take(120),
)

private fun TaskPayloadRow.toRecordPayload(): TaskRecordPayload {
    val storedSpec = runCatching { TaskSpec.fromJson(this.spec) }.getOrNull()
        ?.toRecordSpec()
        ?: TaskRecordSpec()
    val outputJson = runCatching { JSONArray(outputs) }.getOrNull()
    val parsedOutputs = if (outputJson == null) {
        emptyList()
    } else {
        (0 until outputJson.length()).mapNotNull { index ->
            TaskOutput.fromJson(outputJson.opt(index))
        }
    }
    return TaskRecordPayload(storedSpec, parsedOutputs, outputsValid = outputJson != null)
}

internal fun taskDisplayTitle(storedTitle: String, result: ParseResult?): String = when {
    result == null -> storedTitle
    else -> taskDisplayTitle(storedTitle, result.description)
}

private fun taskPreferredTitle(result: ParseResult): String {
    val documentTitle = result.document?.title.orEmpty().normalizedTaskTitle()
    val description = result.description.normalizedTaskTitle()
    return when (result.kind) {
        MediaKind.DOCUMENT -> documentTitle.ifBlank { description }
        else -> description.ifBlank { documentTitle }
    }.ifBlank {
        result.author.normalizedTaskTitle()
    }.ifBlank {
        result.contentId
    }
}

private fun taskDisplayTitle(
    storedTitle: String,
    description: String,
): String = description.normalizedTaskTitle().ifBlank { storedTitle }

private fun String.normalizedTaskTitle(): String =
    replace(Regex("\\s+"), " ").trim().take(60)

private fun List<TaskOutput>.toJson(): String = JSONArray().apply {
    forEach { put(it.toJson()) }
}.toString()
