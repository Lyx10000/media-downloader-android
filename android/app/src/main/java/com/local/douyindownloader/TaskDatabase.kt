package com.local.douyindownloader

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "stage") val stage: String,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "spec") val spec: String,
    @ColumnInfo(name = "outputs") val outputs: String,
    @ColumnInfo(name = "error") val error: String,
    @ColumnInfo(name = "file_status", defaultValue = "'UNKNOWN'") val fileStatus: String,
)

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TaskEntity)

    @Query("SELECT spec FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getSpec(taskId: String): String?

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun get(taskId: String): TaskEntity?

    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    suspend fun list(): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY created_at DESC")
    fun observe(): Flow<List<TaskEntity>>

    @Query(
        """
        UPDATE tasks SET status = :status, stage = :stage, progress = :progress, error = :error
        WHERE id = :taskId AND status != 'DELETING'
        """,
    )
    suspend fun updateUnlessDeleting(
        taskId: String,
        status: String,
        stage: String,
        progress: Int,
        error: String,
    )

    @Query(
        """
        UPDATE tasks SET status = :status, stage = :stage, progress = :progress, error = :error
        WHERE id = :taskId
        """,
    )
    suspend fun updateIncludingDeleting(
        taskId: String,
        status: String,
        stage: String,
        progress: Int,
        error: String,
    )

    @Query("UPDATE tasks SET spec = :spec WHERE id = :taskId AND status != 'DELETING'")
    suspend fun replaceSpec(taskId: String, spec: String)

    @Query("UPDATE tasks SET file_status = :fileState WHERE id = :taskId AND status != 'DELETING'")
    suspend fun updateFileState(taskId: String, fileState: String)

    @Query(
        """
        UPDATE tasks SET outputs = '[]', file_status = :fileState
        WHERE id = :taskId AND status != 'DELETING'
        """,
    )
    suspend fun clearOutputs(taskId: String, fileState: String)

    @Query("UPDATE tasks SET outputs = :outputs, file_status = :fileState WHERE id = :taskId")
    suspend fun replaceOutputs(taskId: String, outputs: String, fileState: String)

    @Query(
        """
        UPDATE tasks SET status = 'FAILED', stage = '部分内容删除失败', progress = :progress,
        error = :error, file_status = 'DELETE_FAILED' WHERE id = :taskId
        """,
    )
    suspend fun setDeleteFailed(taskId: String, progress: Int, error: String)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun delete(taskId: String)

    @Query(
        """
        UPDATE tasks SET status = 'COMPLETE', stage = :stage, progress = 100,
        outputs = :outputs, error = '', file_status = 'AVAILABLE'
        WHERE id = :taskId AND status != 'DELETING'
        """,
    )
    suspend fun complete(taskId: String, outputs: String, stage: String)
}

@Database(entities = [TaskEntity::class], version = 3, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        const val DATABASE_NAME = "downloads.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE tasks ADD COLUMN file_status TEXT NOT NULL DEFAULT 'UNKNOWN'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tasks_room (
                        id TEXT NOT NULL PRIMARY KEY,
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
                database.execSQL(
                    """
                    INSERT INTO tasks_room (
                        id, created_at, status, stage, progress, title, spec, outputs, error,
                        file_status
                    )
                    SELECT id, created_at, status, stage, progress, title, spec, outputs, error,
                        file_status
                    FROM tasks
                    """.trimIndent(),
                )
                database.execSQL("DROP TABLE tasks")
                database.execSQL("ALTER TABLE tasks_room RENAME TO tasks")
            }
        }

    }
}
