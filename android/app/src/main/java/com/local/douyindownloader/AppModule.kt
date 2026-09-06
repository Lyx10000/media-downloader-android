package com.local.douyindownloader

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideTaskDatabase(@ApplicationContext context: Context): TaskDatabase =
        Room.databaseBuilder(context, TaskDatabase::class.java, TaskDatabase.DATABASE_NAME)
            .addMigrations(
                TaskDatabase.MIGRATION_1_2,
                TaskDatabase.MIGRATION_2_3,
                TaskDatabase.MIGRATION_3_4,
                TaskDatabase.MIGRATION_4_5,
                TaskDatabase.MIGRATION_5_6,
            )
            .build()

    @Provides
    fun provideTaskDao(database: TaskDatabase): TaskDao = database.taskDao()

    @Provides
    fun provideCreatorDao(database: TaskDatabase): CreatorDao = database.creatorDao()

    @Provides
    fun provideCreatorWorkDao(database: TaskDatabase): CreatorWorkDao = database.creatorWorkDao()

    @Provides
    fun provideDownloadBatchDao(database: TaskDatabase): DownloadBatchDao = database.downloadBatchDao()

    @Provides
    fun provideCreatorPageDao(database: TaskDatabase): CreatorPageDao = database.creatorPageDao()

    @Provides
    fun provideZhihuQuestionDao(database: TaskDatabase): ZhihuQuestionDao = database.zhihuQuestionDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(context, "settings")),
        produceFile = { context.preferencesDataStoreFile("settings") },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDownloadTaskRepository(
        repository: RoomDownloadTaskRepository,
    ): DownloadTaskRepository

    @Binds
    @Singleton
    internal abstract fun bindManagedFileGateway(
        gateway: AndroidManagedFileGateway,
    ): ManagedFileGateway

    @Binds
    @Singleton
    internal abstract fun bindParserHttpClient(
        client: OkHttpParserClient,
    ): ParserHttpClient
}
