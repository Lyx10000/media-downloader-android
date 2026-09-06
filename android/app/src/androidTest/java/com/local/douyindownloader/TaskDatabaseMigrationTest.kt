package com.local.douyindownloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskDatabaseMigrationTest {
    private lateinit var context: Context
    private var database: TaskDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migratesVersionOneWithoutLosingTask() = runBlocking {
        createLegacyDatabase(version = 1)
        val entity = openRoom().taskDao().get(TASK_ID)

        assertNotNull(entity)
        assertEquals("UNKNOWN", entity?.fileStatus)
        assertEquals("", entity?.authorKey)
        assertEquals(false, entity?.creatorChild)
        assertEquals("[\"content://media/legacy.mp4\"]", entity?.outputs)
    }

    @Test
    fun migratesVersionTwoWithoutLosingTask() = runBlocking {
        createLegacyDatabase(version = 2)
        val entity = openRoom().taskDao().get(TASK_ID)

        assertNotNull(entity)
        assertEquals("AVAILABLE", entity?.fileStatus)
        assertEquals("COMPLETE", entity?.status)
        assertEquals(100, entity?.progress)
        assertEquals("", entity?.batchId)
    }

    @Test
    fun migratesVersionThreeAndCreatesCreatorTables() = runBlocking {
        createLegacyDatabase(version = 3)
        val room = openRoom()
        val entity = room.taskDao().get(TASK_ID)
        room.creatorDao().upsert(
            CreatorProfile(
                key = "douyin:stable",
                platform = SourcePlatform.DOUYIN,
                stableId = "stable",
                accountId = "account",
                profileUrl = "https://www.douyin.com/user/stable",
                directoryName = "author_account_stable",
                nickname = "author",
            ).toEntity(),
        )
        room.downloadBatchDao().upsertBatch(
            DownloadBatchEntity("batch", "douyin:stable", 1L, "QUEUED", "{}", 1),
        )
        room.downloadBatchDao().upsertWorks(
            listOf(BatchWorkEntity("batch", "douyin:work", "QUEUED", "", "")),
        )

        assertNotNull(entity)
        assertEquals("", entity?.authorKey)
        assertEquals("author_account_stable", room.creatorDao().get("douyin:stable")?.directoryName)
        assertEquals("", room.downloadBatchDao().getWork("batch", "douyin:work")?.sourceUrl)
        assertEquals(0, room.downloadBatchDao().getWork("batch", "douyin:work")?.attemptCount)
    }

    private fun createLegacyDatabase(version: Int) {
        val file = context.getDatabasePath(TEST_DATABASE)
        File(file.parent.orEmpty()).mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.execSQL(
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
                    error TEXT NOT NULL${if (version >= 2) ",\nfile_status TEXT NOT NULL DEFAULT 'UNKNOWN'" else ""}
                )
                """.trimIndent(),
            )
            val columns = if (version >= 2) {
                "id, created_at, status, stage, progress, title, spec, outputs, error, file_status"
            } else {
                "id, created_at, status, stage, progress, title, spec, outputs, error"
            }
            val values = if (version >= 2) {
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
            } else {
                "?, ?, ?, ?, ?, ?, ?, ?, ?"
            }
            val arguments = mutableListOf<Any>(
                TASK_ID,
                1_700_000_000_000L,
                "COMPLETE",
                "已完成",
                100,
                "旧任务",
                "{}",
                "[\"content://media/legacy.mp4\"]",
                "",
            )
            if (version >= 2) arguments += "AVAILABLE"
            sqlite.execSQL("INSERT INTO tasks ($columns) VALUES ($values)", arguments.toTypedArray())
            sqlite.version = version
        }
    }

    private fun openRoom(): TaskDatabase = Room.databaseBuilder(
        context,
        TaskDatabase::class.java,
        TEST_DATABASE,
    ).addMigrations(
        TaskDatabase.MIGRATION_1_2,
        TaskDatabase.MIGRATION_2_3,
        TaskDatabase.MIGRATION_3_4,
        TaskDatabase.MIGRATION_4_5,
    )
        .build()
        .also { database = it }

    companion object {
        private const val TEST_DATABASE = "migration-test.db"
        private const val TASK_ID = "legacy-task"
    }
}
