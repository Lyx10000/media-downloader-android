package com.local.douyindownloader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class TaskStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "downloads.db",
    null,
    2,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                stage TEXT NOT NULL,
                progress INTEGER NOT NULL,
                title TEXT NOT NULL,
                spec TEXT NOT NULL,
                outputs TEXT NOT NULL,
                error TEXT NOT NULL,
                file_status TEXT NOT NULL DEFAULT 'UNKNOWN'
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN file_status TEXT NOT NULL DEFAULT 'UNKNOWN'")
        }
    }

    fun insert(spec: TaskSpec) {
        val title = spec.result.author.ifBlank {
            spec.result.description.take(30).ifBlank { spec.result.awemeId }
        }
        writableDatabase.insertOrThrow("tasks", null, ContentValues().apply {
            put("id", spec.taskId)
            put("created_at", spec.createdAt)
            put("status", "QUEUED")
            put("stage", "等待下载")
            put("progress", 0)
            put("title", title)
            put("spec", spec.toJson())
            put("outputs", "[]")
            put("error", "")
            put("file_status", FileState.UNKNOWN)
        })
    }

    fun getSpec(taskId: String): TaskSpec? {
        readableDatabase.query(
            "tasks",
            arrayOf("spec"),
            "id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) TaskSpec.fromJson(cursor.getString(0)) else null
        }
    }

    fun update(
        taskId: String,
        status: String,
        stage: String,
        progress: Int,
        error: String = "",
    ) {
        val values = ContentValues().apply {
            put("status", status)
            put("stage", stage)
            put("progress", progress.coerceIn(0, 100))
            put("error", Redactor.sanitize(error))
        }
        val allowDeleting = status == "DELETING"
        writableDatabase.update(
            "tasks",
            values,
            if (allowDeleting) "id = ?" else "id = ? AND status != 'DELETING'",
            arrayOf(taskId),
        )
    }

    fun replaceSpec(taskId: String, spec: TaskSpec) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("spec", spec.toJson())
        }, "id = ? AND status != 'DELETING'", arrayOf(taskId))
    }

    fun updateFileState(taskId: String, fileState: String) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("file_status", fileState)
        }, "id = ? AND status != ?", arrayOf(taskId, "DELETING"))
    }

    fun clearOutputs(taskId: String, fileState: String = FileState.UNKNOWN) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("outputs", "[]")
            put("file_status", fileState)
        }, "id = ? AND status != 'DELETING'", arrayOf(taskId))
    }

    fun replaceOutputs(taskId: String, outputs: List<TaskOutput>) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("outputs", JSONArray().apply { outputs.forEach { put(it.toJson()) } }.toString())
            put("file_status", if (outputs.isEmpty()) FileState.UNKNOWN else FileState.AVAILABLE)
        }, "id = ?", arrayOf(taskId))
    }

    fun setDeleteFailed(taskId: String, progress: Int, error: String) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("status", "FAILED")
            put("stage", "部分内容删除失败")
            put("progress", progress.coerceIn(0, 100))
            put("error", Redactor.sanitize(error))
            put("file_status", FileState.DELETE_FAILED)
        }, "id = ?", arrayOf(taskId))
    }

    fun delete(taskId: String) {
        writableDatabase.delete("tasks", "id = ?", arrayOf(taskId))
    }

    fun complete(taskId: String, outputs: List<TaskOutput>) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("status", "COMPLETE")
            put("stage", "已完成")
            put("progress", 100)
            put("outputs", JSONArray().apply { outputs.forEach { put(it.toJson()) } }.toString())
            put("error", "")
            put("file_status", FileState.AVAILABLE)
        }, "id = ? AND status != 'DELETING'", arrayOf(taskId))
    }

    fun list(): List<TaskRecord> {
        val result = mutableListOf<TaskRecord>()
        readableDatabase.query(
            "tasks",
            arrayOf(
                "id", "created_at", "status", "stage", "progress", "title", "outputs", "error",
                "file_status",
            ),
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val outputJson = JSONArray(cursor.getString(6))
                val outputs = (0 until outputJson.length()).mapNotNull { index ->
                    TaskOutput.fromJson(outputJson.opt(index))
                }
                result += TaskRecord(
                    id = cursor.getString(0),
                    createdAt = cursor.getLong(1),
                    status = cursor.getString(2),
                    stage = cursor.getString(3),
                    progress = cursor.getInt(4),
                    title = cursor.getString(5),
                    outputs = outputs,
                    error = cursor.getString(7),
                    fileState = cursor.getString(8),
                )
            }
        }
        return result
    }

    fun get(taskId: String): TaskRecord? = list().firstOrNull { it.id == taskId }
}
