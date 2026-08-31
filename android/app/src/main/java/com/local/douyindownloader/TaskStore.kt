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
    1,
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
                error TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
        writableDatabase.update("tasks", ContentValues().apply {
            put("status", status)
            put("stage", stage)
            put("progress", progress.coerceIn(0, 100))
            put("error", Redactor.sanitize(error))
        }, "id = ?", arrayOf(taskId))
    }

    fun complete(taskId: String, outputUris: List<String>) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("status", "COMPLETE")
            put("stage", "已完成")
            put("progress", 100)
            put("outputs", JSONArray(outputUris).toString())
            put("error", "")
        }, "id = ?", arrayOf(taskId))
    }

    fun list(): List<TaskRecord> {
        val result = mutableListOf<TaskRecord>()
        readableDatabase.query(
            "tasks",
            arrayOf("id", "created_at", "status", "stage", "progress", "title", "outputs", "error"),
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val outputs = JSONArray(cursor.getString(6))
                result += TaskRecord(
                    id = cursor.getString(0),
                    createdAt = cursor.getLong(1),
                    status = cursor.getString(2),
                    stage = cursor.getString(3),
                    progress = cursor.getInt(4),
                    title = cursor.getString(5),
                    outputUris = (0 until outputs.length()).map { outputs.getString(it) },
                    error = cursor.getString(7),
                )
            }
        }
        return result
    }
}

