package com.local.douyindownloader

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
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
    private val inspector = StorageInspector(application)
    private val deletionCoordinator = TaskDeletionCoordinator(application)
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
    @Volatile private var refreshingTasks = false
    private var lastStorageScanAt = 0L

    init {
        refreshTasks()
        refreshLogs()
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                refreshTasks(forceStorageCheck = false)
            }
        }
    }

    fun setIncomingText(text: String) {
        if (text.isNotBlank()) inputText = text
    }

    fun clearInput() {
        inputText = ""
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
        val createdAt = System.currentTimeMillis()
        val storageMode = if (customTreeUri.isNullOrBlank()) StorageMode.DEFAULT else StorageMode.SAF
        val spec = TaskSpec(
            taskId = id,
            createdAt = createdAt,
            result = result,
            variantIndex = selectedVariant,
            mode = selectedMode,
            sourceText = inputText,
            storageMode = storageMode,
            storageRoot = customTreeUri.orEmpty(),
            taskFolder = taskFolderName(createdAt, id),
        )
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
        val cookieHeader = CookieManager.getInstance()
            .getCookie("https://www.douyin.com/").orEmpty()
        viewModelScope.launch {
            val originalSpec = withContext(Dispatchers.IO) { store.getSpec(task.id) }
            if (originalSpec == null || originalSpec.result.awemeId.isBlank()) {
                message = "旧任务缺少作品 ID，无法自动重新解析"
                return@launch
            }

            val resolvedStorage = resolveStorageForRetry(originalSpec) ?: return@launch
            store.update(task.id, "RUNNING", "正在重新解析作品", 0)
            refreshTasks()
            logger.event(task.id, "REDOWNLOAD", "REPARSE_STARTED", JSONObject().apply {
                put("aweme_id", originalSpec.result.awemeId)
                put("kind", originalSpec.result.kind)
            })
            val refreshed = parser.parse(originalSpec.stableSource(), cookieHeader)
            if (!refreshed.ok) {
                val hint = if (refreshed.errorCode == "AUTH_OR_RISK") {
                    "请到设置中登录或刷新抖音环境后重试"
                } else refreshed.message
                store.update(task.id, "FAILED", "重新解析失败", 0, hint)
                logger.event(task.id, "REDOWNLOAD", "REPARSE_FAILED", JSONObject().apply {
                    put("code", refreshed.errorCode)
                    put("message", refreshed.message)
                })
                message = hint
                refreshTasks(forceStorageCheck = true)
                return@launch
            }

            val previous = originalSpec.result.variants.getOrNull(originalSpec.variantIndex)
            val match = if (refreshed.kind == "image") VariantMatch(0, true)
            else matchVariant(previous, refreshed.variants)
            if (refreshed.kind != "image" && match.index < 0) {
                store.update(task.id, "FAILED", "重新解析失败", 0, "当前没有可下载的视频档位")
                message = "当前没有可下载的视频档位"
                refreshTasks()
                return@launch
            }

            val cleanup = deletionCoordinator.deleteOutputsForRedownload(task.id)
            if (!cleanup.success) {
                message = cleanup.message
                refreshTasks(forceStorageCheck = true)
                return@launch
            }

            val now = System.currentTimeMillis()
            val updatedSpec = originalSpec.copy(
                result = refreshed,
                variantIndex = match.index.coerceAtLeast(0),
                sourceText = originalSpec.stableSource(),
                storageMode = resolvedStorage.first,
                storageRoot = resolvedStorage.second,
                taskFolder = taskFolderName(now, task.id),
            )
            store.replaceSpec(task.id, updatedSpec)
            store.update(task.id, "QUEUED", "等待重新下载", 0)
            logger.event(task.id, "REDOWNLOAD", "REPARSE_COMPLETE", JSONObject().apply {
                put("variant", match.index)
                put("exact_match", match.exact)
                put("images", refreshed.imageCandidates.size)
            })
            enqueue(task.id, ExistingWorkPolicy.REPLACE)
            message = if (refreshed.kind != "image" && !match.exact) {
                if (previous == null) "已选择当前可获得的最高档位"
                else "原清晰度已不可用，已选择当前最接近的档位"
            } else {
                "已重新解析并开始下载"
            }
            refreshTasks()
        }
    }

    fun deleteTask(task: TaskRecord, deleteFiles: Boolean) {
        viewModelScope.launch {
            val result = deletionCoordinator.deleteTask(task.id, deleteFiles)
            message = result.message
            refreshTasks(forceStorageCheck = true)
        }
    }

    fun requiresAllFilesAccess(task: TaskRecord): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            return false
        }
        val spec = store.getSpec(task.id) ?: return false
        if (spec.storageMode == StorageMode.SAF) return false
        if (spec.storageMode == StorageMode.LEGACY) {
            return task.outputs.any {
                runCatching { Uri.parse(it.uri).authority == "media" }.getOrDefault(false)
            }
        }
        return true
    }

    fun hasReplacementStorage(task: TaskRecord): Boolean {
        val spec = store.getSpec(task.id) ?: return false
        if (spec.storageMode != StorageMode.SAF || inspector.isTreeAvailable(spec.storageRoot)) {
            return false
        }
        val replacement = customTreeUri ?: return false
        return replacement != spec.storageRoot && inspector.isTreeAvailable(replacement)
    }

    fun onAppForeground() = refreshTasks(forceStorageCheck = true)

    fun onTasksVisible() = refreshTasks(forceStorageCheck = true)

    fun refreshTasks(forceStorageCheck: Boolean = false) {
        if (refreshingTasks) return
        refreshingTasks = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var records = store.list()
                val now = System.currentTimeMillis()
                if (forceStorageCheck || now - lastStorageScanAt >= 15_000) {
                    records.forEach { task ->
                        if (task.status != "DELETING") {
                            val state = inspector.inspect(task, store.getSpec(task.id))
                            if (state != task.fileState) {
                                store.updateFileState(task.id, state)
                                logger.event(task.id, "STORAGE", "FILE_STATE_CHANGED", JSONObject().apply {
                                    put("from", task.fileState)
                                    put("to", state)
                                    put("outputs", task.outputs.size)
                                })
                            }
                        }
                    }
                    lastStorageScanAt = now
                    records = store.list()
                }
                withContext(Dispatchers.Main) { tasks = records }
            } finally {
                refreshingTasks = false
            }
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

    private fun resolveStorageForRetry(spec: TaskSpec): Pair<String, String>? {
        return when (spec.storageMode) {
            StorageMode.SAF -> {
                if (inspector.isTreeAvailable(spec.storageRoot)) {
                    StorageMode.SAF to spec.storageRoot
                } else {
                    val replacement = customTreeUri
                    if (replacement.isNullOrBlank() || !inspector.isTreeAvailable(replacement)) {
                        message = "保存目录已失效，请重新选择目录后再下载"
                        null
                    } else {
                        StorageMode.SAF to replacement
                    }
                }
            }
            StorageMode.DEFAULT -> StorageMode.DEFAULT to ""
            else -> {
                val currentTree = customTreeUri
                if (!currentTree.isNullOrBlank() && inspector.isTreeAvailable(currentTree)) {
                    StorageMode.SAF to currentTree
                } else {
                    StorageMode.DEFAULT to ""
                }
            }
        }
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
