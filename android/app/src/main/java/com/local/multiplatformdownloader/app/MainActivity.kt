package com.local.multiplatformdownloader.app

import com.local.multiplatformdownloader.app.navigation.DownloaderApp
import com.local.multiplatformdownloader.app.theme.DownloaderTheme
import com.local.multiplatformdownloader.core.download.AdaptiveDownloadController
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryViewModel
import com.local.multiplatformdownloader.feature.home.MainViewModel
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveViewModel

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Bundle
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var adaptiveDownloadController: AdaptiveDownloadController

    private val viewModel by viewModels<MainViewModel>()
    private val creatorViewModel by viewModels<CreatorLibraryViewModel>()
    private val questionArchiveViewModel by viewModels<ZhihuQuestionArchiveViewModel>()
    private val frameMetricsThread = HandlerThread("download-frame-metrics")
    private var frameMetricsHandler: Handler? = null
    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        adaptiveDownloadController.recordFrame(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        frameMetricsThread.start()
        frameMetricsHandler = Handler(frameMetricsThread.looper)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            DownloaderTheme {
                DownloaderApp(viewModel, creatorViewModel, questionArchiveViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForeground()
    }

    override fun onStart() {
        super.onStart()
        window.addOnFrameMetricsAvailableListener(frameMetricsListener, frameMetricsHandler)
    }

    override fun onStop() {
        window.removeOnFrameMetricsAvailableListener(frameMetricsListener)
        viewModel.onAppBackground()
        super.onStop()
    }

    override fun onDestroy() {
        frameMetricsThread.quitSafely()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                viewModel.setIncomingText(it)
                viewModel.beginParse()
            }
        }
    }
}
