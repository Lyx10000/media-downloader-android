package com.local.douyindownloader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

sealed interface ParseUiState {
    data object Idle : ParseUiState
    data class LoadingWeb(val url: String) : ParseUiState
    data object Parsing : ParseUiState
    data class Ready(val result: ParseResult) : ParseUiState
    data class Error(val code: String, val message: String) : ParseUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val parser = ParserGateway()
    private val store = TaskStore(application)
    private val logger = DiagnosticLogger(application)
    private val preferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var inputText by mutableStateOf("")
    var parseState: ParseUiState by mutableStateOf(ParseUiState.Idle)
        private set
    var selectedVariant by mutableIntStateOf(0)
    var selectedMode by mutableStateOf(preferences.getString("default_mode", "merge_keep") ?: "merge_keep")
    var tasks by mutableStateOf(emptyList<TaskRecord>())
        private set
    var logText by mutableStateOf("")
        private set
    var message by mutableStateOf("")
        private set
    var preferH264 by mutableStateOf(preferences.getBoolean("prefer_h264", false))
        private set
    var customTreeUri by mutableStateOf(preferences.getString("custom_tree_uri", null))
        private set

    private var sessionId = ""
    private var parsingStarted = false

    init {
        refreshTasks()
        refreshLogs()
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                refreshTasks()
            }
        }
    }

    fun setIncomingText(text: String) {
        if (text.isNotBlank()) inputText = text
    }

    fun beginParse() {
        val url = extractDouyinUrl(inputText)
        if (url == null) {
            message = "没有找到抖音链接"
            return
        }
        sessionId = UUID.randomUUID().toString()
        parsingStarted = false
        logger.event(sessionId, "INPUT", "LINK_ACCEPTED", JSONObject().put("host", Uri.parse(url).host))
        parseState = ParseUiState.LoadingWeb(url)
    }

    fun parseWithCookies(cookieHeader: String) {
        if (parsingStarted) return
        parsingStarted = true
        parseState = ParseUiState.Parsing
        logger.event(sessionId, "COOKIE", "COOKIE_READY", JSONObject().put("present", cookieHeader.isNotBlank()))
        viewModelScope.launch {
            val result = parser.parse(inputText, cookieHeader)
            if (result.ok) {
                logger.event(sessionId, "PARSE", "DETAIL_PARSED", JSONObject().apply {
                    put("aweme_id", result.awemeId)
                    put("kind", result.kind)
                    put("variants", result.variants.size)
                    put("images", result.imageUrls.size)
                    put("separate_audio", result.audioUrls.isNotEmpty())
                })
                logger.saveResponseShape(sessionId, result.responseShape)
                selectedVariant = preferredVariant(result)
                parseState = ParseUiState.Ready(result)
            } else {
                logger.event(sessionId, "PARSE", "PARSE_FAILED", JSONObject().apply {
                    put("code", result.errorCode)
                    put("message", result.message)
                })
                parseState = ParseUiState.Error(result.errorCode, result.message)
            }
            refreshLogs()
        }
    }

    fun retryParse() {
        parsingStarted = false
        beginParse()
    }

    fun resetParse() {
        parseState = ParseUiState.Idle
        parsingStarted = false
    }

    fun selectVariant(index: Int) {
        selectedVariant = index
    }

    fun selectMode(mode: String) {
        selectedMode = mode
    }

    fun queueDownload(result: ParseResult) {
        val id = sessionId.ifBlank { UUID.randomUUID().toString() }
        val spec = TaskSpec(id, System.currentTimeMillis(), result, selectedVariant, selectedMode)
        store.insert(spec)
        logger.event(id, "QUALITY", "DOWNLOAD_CONFIRMED", JSONObject().apply {
            put("variant", selectedVariant)
            put("mode", selectedMode)
        })
        enqueue(id, ExistingWorkPolicy.KEEP)
        message = "已加入后台下载"
        resetParse()
        refreshTasks()
    }

    fun cancelTask(task: TaskRecord) {
        WorkManager.getInstance(getApplication()).cancelAllWorkByTag(task.id)
        store.update(task.id, "CANCELLED", "已取消", task.progress)
        logger.event(task.id, "DOWNLOAD", "CANCEL_REQUESTED")
        refreshTasks()
    }

    fun retryTask(task: TaskRecord) {
        store.update(task.id, "QUEUED", "等待重试", 0)
        logger.event(task.id, "DOWNLOAD", "RETRY_REQUESTED")
        enqueue(task.id, ExistingWorkPolicy.REPLACE)
        refreshTasks()
    }

    fun refreshTasks() {
        viewModelScope.launch(Dispatchers.IO) {
            val records = store.list()
            withContext(Dispatchers.Main) { tasks = records }
        }
    }

    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val text = logger.readRecent()
            withContext(Dispatchers.Main) { logText = text }
        }
    }

    fun exportLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { logger.export() }
                .onSuccess { uri -> withContext(Dispatchers.Main) { message = "诊断包已保存：$uri" } }
                .onFailure { error -> withContext(Dispatchers.Main) { message = "导出失败：${error.message}" } }
        }
    }

    fun clearLogs() {
        logger.clear()
        refreshLogs()
        message = "日志已清理"
    }

    fun updatePreferH264(value: Boolean) {
        preferH264 = value
        preferences.edit().putBoolean("prefer_h264", value).apply()
    }

    fun setDefaultMode(mode: String) {
        selectedMode = mode
        preferences.edit().putString("default_mode", mode).apply()
    }

    fun setCustomTree(uri: Uri?) {
        customTreeUri = uri?.toString()
        preferences.edit().apply {
            if (uri == null) remove("custom_tree_uri") else putString("custom_tree_uri", uri.toString())
        }.apply()
        message = if (uri == null) "已恢复默认下载目录" else "已保存自定义目录"
    }

    fun consumeMessage(): String {
        val current = message
        message = ""
        return current
    }

    private fun preferredVariant(result: ParseResult): Int {
        if (!preferH264 || result.variants.isEmpty()) return 0
        val highest = result.variants.first()
        return result.variants.indexOfFirst {
            it.width == highest.width && it.height == highest.height && it.codec.contains("264")
        }.takeIf { it >= 0 } ?: 0
    }

    private fun enqueue(taskId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putString(DownloadWorker.KEY_TASK_ID, taskId).build())
            .addTag(taskId)
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniqueWork("douyin-$taskId", policy, request)
    }

    companion object {
        private val douyinUrl = Regex(
            "https?://[^\\s]*?(?:douyin\\.com|iesdouyin\\.com)[^\\s]*",
            RegexOption.IGNORE_CASE,
        )

        fun extractDouyinUrl(text: String): String? = douyinUrl.find(text)?.value
    }
}
