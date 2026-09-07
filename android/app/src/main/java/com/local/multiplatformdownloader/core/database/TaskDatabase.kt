package com.local.multiplatformdownloader.core.database

import com.local.multiplatformdownloader.feature.creator.BatchWorkEntity
import com.local.multiplatformdownloader.feature.creator.CreatorDao
import com.local.multiplatformdownloader.feature.creator.CreatorEntity
import com.local.multiplatformdownloader.feature.creator.CreatorPageDao
import com.local.multiplatformdownloader.feature.creator.CreatorPageEntity
import com.local.multiplatformdownloader.feature.creator.CreatorWorkDao
import com.local.multiplatformdownloader.feature.creator.CreatorWorkEntity
import com.local.multiplatformdownloader.feature.creator.DownloadBatchDao
import com.local.multiplatformdownloader.feature.creator.DownloadBatchEntity
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionAnswerEntity
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionDao
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionEntity

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks", indices = [Index("author_key")])
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
    @ColumnInfo(name = "author_key", defaultValue = "''") val authorKey: String,
    @ColumnInfo(name = "batch_id", defaultValue = "''") val batchId: String,
    @ColumnInfo(name = "creator_child", defaultValue = "0") val creatorChild: Boolean,
)

/**
 * Lightweight row used by task lists and observers.
 *
 * Task specs can contain complete article bodies. Keeping those payloads out of a multi-row
 * CursorWindow prevents a large archive from crashing the process when Room invalidates a task
 * list after an update or deletion.
 */
data class TaskIndexRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "stage") val stage: String,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "error") val error: String,
    @ColumnInfo(name = "file_status") val fileStatus: String,
    @ColumnInfo(name = "author_key") val authorKey: String,
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "creator_child") val creatorChild: Boolean,
)

data class TaskPayloadRow(
    @ColumnInfo(name = "spec") val spec: String,
    @ColumnInfo(name = "outputs") val outputs: String,
)

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TaskEntity)

    @Query("SELECT spec FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getSpec(taskId: String): String?

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun get(taskId: String): TaskEntity?

    @Query(
        """
        SELECT id, created_at, status, stage, progress, title, error, file_status,
            author_key, batch_id, creator_child
        FROM tasks WHERE creator_child = 0 ORDER BY created_at DESC
        """,
    )
    suspend fun listIndex(): List<TaskIndexRow>

    @Query(
        """
        SELECT id, created_at, status, stage, progress, title, error, file_status,
            author_key, batch_id, creator_child
        FROM tasks ORDER BY created_at DESC
        """,
    )
    suspend fun listAllIndex(): List<TaskIndexRow>

    @Query(
        """
        SELECT id, created_at, status, stage, progress, title, error, file_status,
            author_key, batch_id, creator_child
        FROM tasks WHERE creator_child = 0 ORDER BY created_at DESC
        """,
    )
    fun observeIndex(): Flow<List<TaskIndexRow>>

    @Query(
        """
        SELECT id, created_at, status, stage, progress, title, error, file_status,
            author_key, batch_id, creator_child
        FROM tasks ORDER BY created_at DESC
        """,
    )
    fun observeAllIndex(): Flow<List<TaskIndexRow>>

    @Query(
        """
        SELECT id, created_at, status, stage, progress, title, error, file_status,
            author_key, batch_id, creator_child
        FROM tasks WHERE author_key = :authorKey ORDER BY created_at DESC
        """,
    )
    suspend fun listForAuthorIndex(authorKey: String): List<TaskIndexRow>

    @Query("SELECT spec, outputs FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getPayload(taskId: String): TaskPayloadRow?

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

    @Query("UPDATE tasks SET author_key = :authorKey WHERE id = :taskId AND status != 'DELETING'")
    suspend fun updateAuthorKey(taskId: String, authorKey: String)

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

@Database(
    entities = [
        TaskEntity::class,
        CreatorEntity::class,
        CreatorWorkEntity::class,
        DownloadBatchEntity::class,
        BatchWorkEntity::class,
        CreatorPageEntity::class,
        ZhihuQuestionEntity::class,
        ZhihuQuestionAnswerEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun creatorDao(): CreatorDao
    abstract fun creatorWorkDao(): CreatorWorkDao
    abstract fun downloadBatchDao(): DownloadBatchDao
    abstract fun creatorPageDao(): CreatorPageDao
    abstract fun zhihuQuestionDao(): ZhihuQuestionDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN author_key TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE tasks ADD COLUMN batch_id TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE tasks ADD COLUMN creator_child INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS creators (
                        creator_key TEXT NOT NULL PRIMARY KEY,
                        platform TEXT NOT NULL,
                        stable_id TEXT NOT NULL,
                        account_id TEXT NOT NULL,
                        profile_url TEXT NOT NULL,
                        directory_name TEXT NOT NULL,
                        avatar_url TEXT NOT NULL,
                        nickname TEXT NOT NULL,
                        bio TEXT NOT NULL,
                        location TEXT NOT NULL,
                        metrics_json TEXT NOT NULL,
                        account_status TEXT NOT NULL,
                        followed INTEGER NOT NULL,
                        archived INTEGER NOT NULL,
                        added_at INTEGER NOT NULL,
                        refreshed_at INTEGER NOT NULL,
                        refresh_error TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS creator_works (
                        work_key TEXT NOT NULL PRIMARY KEY,
                        creator_key TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        content_id TEXT NOT NULL,
                        canonical_url TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        cover_url TEXT NOT NULL,
                        published_at INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        approximate_bytes INTEGER NOT NULL,
                        remote_status TEXT NOT NULL,
                        last_seen_at INTEGER NOT NULL,
                        page_number INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_batches (
                        batch_id TEXT NOT NULL PRIMARY KEY,
                        creator_key TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        settings_json TEXT NOT NULL,
                        selected_count INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS batch_works (
                        batch_id TEXT NOT NULL,
                        work_key TEXT NOT NULL,
                        status TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        error TEXT NOT NULL,
                        PRIMARY KEY(batch_id, work_key)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS creator_pages (
                        creator_key TEXT NOT NULL,
                        page_number INTEGER NOT NULL,
                        cursor TEXT NOT NULL,
                        next_cursor TEXT NOT NULL,
                        has_more INTEGER NOT NULL,
                        PRIMARY KEY(creator_key, page_number)
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_creator_works_creator_key ON creator_works(creator_key)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_author_key ON tasks(author_key)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE batch_works ADD COLUMN source_url TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL(
                    "ALTER TABLE batch_works ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "UPDATE batch_works SET status = 'WEB_REQUIRED' WHERE status = 'PARSING'",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS zhihu_questions (
                        question_id TEXT NOT NULL PRIMARY KEY,
                        parent_task_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        canonical_url TEXT NOT NULL,
                        answer_count INTEGER NOT NULL,
                        next_offset INTEGER NOT NULL,
                        has_more INTEGER NOT NULL,
                        scope TEXT NOT NULL,
                        include_comments INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        task_folder TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        refreshed_at INTEGER NOT NULL,
                        error TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS zhihu_question_answers (
                        question_id TEXT NOT NULL,
                        answer_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        author TEXT NOT NULL,
                        excerpt TEXT NOT NULL,
                        voteup_count INTEGER NOT NULL,
                        comment_count INTEGER NOT NULL,
                        canonical_url TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        error TEXT NOT NULL,
                        PRIMARY KEY(question_id, answer_id)
                    )
                    """.trimIndent(),
                )
            }
        }

    }
}
