package com.local.multiplatformdownloader.app

import com.local.multiplatformdownloader.feature.download.DownloadWorker
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Process
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltAndroidApp
class DownloaderApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: AppWorkerFactory
    @Inject lateinit var diagnosticLogger: DiagnosticLogger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        val channel = NotificationChannel(
            DownloadWorker.CHANNEL_ID,
            "下载任务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "显示媒体下载和处理进度" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { diagnosticLogger.uncaughtException(thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
