package com.local.douyindownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class DownloaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val channel = NotificationChannel(
            DownloadWorker.CHANNEL_ID,
            "下载任务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "显示抖音媒体下载和处理进度" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

