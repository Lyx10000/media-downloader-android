package com.local.douyindownloader

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

sealed interface ParseUiState {
    data object Idle : ParseUiState
    data class LoadingWeb(val url: String) : ParseUiState
    data object Parsing : ParseUiState
    data class Ready(val result: ParseResult) : ParseUiState
    data class Error(val code: String, val message: String) : ParseUiState
}

private data class TaskCapabilities(
    val requiresAllFilesAccess: Boolean = false,
    val hasReplacementStorage: Boolean = false,
)

data class MainUiState(
    val inputText: String = "",
    val parseState: ParseUiState = ParseUiState.Idle,
    val selectedVariant: Int = 0,
    val selectedMode: DownloadMode = DownloadMode.MERGE_KEEP,
    val tasks: List<TaskRecord> = emptyList(),
    val logText: String = "",
    val message: String = "",
    val preferH264: Boolean = false,
    val customTreeUri: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val parser: ParserGateway,
    private val store: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val inspector: StorageInspector,
    private val deletionCoordinator: TaskDeletionCoordinator,
    private val settingsRepository: SettingsRepository,
    private val redownloadCoordinator: TaskRedownloadCoordinator,
    private val scheduler: DownloadScheduler,
    private val fileStateRefresher: TaskFileStateRefresher,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    var inputText: String
        get() = _uiState.value.inputText
        set(value) = _uiState.update { it.copy(inputText = value) }
    var parseState: ParseUiState
        get() = _uiState.value.parseState
        private set(value) = _uiState.update { it.copy(parseState = value) }
    var selectedVariant: Int
        get() = _uiState.value.selectedVariant
        set(value) = _uiState.update { it.copy(selectedVariant = value) }
    var selectedMode: DownloadMode
        get() = _uiState.value.selectedMode
        private set(value) = _uiState.update { it.copy(selectedMode = value) }
    var tasks: List<TaskRecord>
        get() = _uiState.value.tasks
        private set(value) = _uiState.update { it.copy(tasks = value) }
    var logText: String
        get() = _uiState.value.logText
        private set(value) = _uiState.update { it.copy(logText = value) }
    var message: String
        get() = _uiState.value.message
        private set(value) = _uiState.update { it.copy(message = value) }
    var preferH264: Boolean
        get() = _uiState.value.preferH264
        private set(value) = _uiState.update { it.copy(preferH264 = value) }
    var customTreeUri: String?
        get() = _uiState.value.customTreeUri
        private set(value) = _uiState.update { it.copy(customTreeUri = value) }

    private var sessionId = ""
    private var parsingStarted = false
    private val refreshMutex = Mutex()
    private var taskCapabilities = emptyMap<String, TaskCapabilities>()
    private var tasksVisible = false

    init {
        refreshLogs()
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                selectedMode = settings.defaultMode
                preferH264 = settings.preferH264
                customTreeUri = settings.customTreeUri
                refreshTaskMetadata(tasks)
            }
        }
        viewModelScope.launch {
            store.observe().collectLatest { records ->
                tasks = records
                refreshTaskMetadata(records)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (tasksVisible && tasks.isNotEmpty()) refreshTasks()
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
                    put("kind", result.kind.wireValue)
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

    fun selectMode(mode: DownloadMode) {
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
        viewModelScope.launch {
            runCatching { store.insert(spec) }
                .onSuccess {
                    logger.event(id, "QUALITY", "DOWNLOAD_CONFIRMED", JSONObject().apply {
                        put("variant", selectedVariant)
                        put("mode", selectedMode.wireValue)
                    })
                    scheduler.enqueue(id, ExistingWorkPolicy.KEEP)
                    message = "已加入后台下载"
                    resetParse()
                }
                .onFailure { error -> message = "创建下载任务失败：${error.message}" }
        }
    }

    fun cancelTask(task: TaskRecord) {
        scheduler.cancel(task.id)
        viewModelScope.launch {
            store.update(task.id, TaskStatus.CANCELLED, "已取消", task.progress)
            logger.event(task.id, "DOWNLOAD", "CANCEL_REQUESTED")
        }
    }

    fun retryTask(task: TaskRecord) {
        val cookieHeader = CookieManager.getInstance()
            .getCookie("https://www.douyin.com/").orEmpty()
        viewModelScope.launch {
            message = redownloadCoordinator.retry(task, cookieHeader, customTreeUri)
            refreshTasks()
        }
    }

    fun deleteTask(task: TaskRecord, deleteFiles: Boolean) {
        viewModelScope.launch {
            val result = deletionCoordinator.deleteTask(task.id, deleteFiles)
            message = result.message
            refreshTasks()
        }
    }

    fun requiresAllFilesAccess(task: TaskRecord): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            return false
        }
        return taskCapabilities[task.id]?.requiresAllFilesAccess == true
    }

    fun hasReplacementStorage(task: TaskRecord): Boolean {
        return taskCapabilities[task.id]?.hasReplacementStorage == true
    }

    fun onAppForeground() = refreshTasks()

    fun onTasksVisible() {
        tasksVisible = true
        refreshTasks()
    }

    fun onTasksHidden() {
        tasksVisible = false
    }

    fun refreshTasks() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!refreshMutex.tryLock()) return@launch
            try {
                fileStateRefresher.refresh(store.list())
            } finally {
                refreshMutex.unlock()
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
        viewModelScope.launch(Dispatchers.IO) {
            logger.clear()
            withContext(Dispatchers.Main) {
                refreshLogs()
                message = "日志已清理"
            }
        }
    }

    fun updatePreferH264(value: Boolean) {
        preferH264 = value
        viewModelScope.launch { settingsRepository.setPreferH264(value) }
    }

    fun setDefaultMode(mode: DownloadMode) {
        selectedMode = mode
        viewModelScope.launch { settingsRepository.setDefaultMode(mode) }
    }

    fun setCustomTree(uri: Uri?) {
        customTreeUri = uri?.toString()
        viewModelScope.launch {
            settingsRepository.setCustomTree(uri?.toString())
            refreshTaskMetadata(tasks)
        }
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

    private fun refreshTaskMetadata(records: List<TaskRecord>) {
        viewModelScope.launch(Dispatchers.IO) {
            val specs = records.mapNotNull { task ->
                store.getSpec(task.id)?.let { task.id to it }
            }.toMap()
            val replacement = customTreeUri
            val capabilities = records.associate { task ->
                val spec = specs[task.id]
                val requiresAccess = if (
                    spec == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    Environment.isExternalStorageManager() || spec.storageMode == StorageMode.SAF
                ) {
                    false
                } else if (spec.storageMode == StorageMode.LEGACY) {
                    task.outputs.any {
                        runCatching { Uri.parse(it.uri).authority == "media" }.getOrDefault(false)
                    }
                } else {
                    true
                }
                val replacementReady = spec?.storageMode == StorageMode.SAF &&
                    !inspector.isTreeAvailable(spec.storageRoot) &&
                    !replacement.isNullOrBlank() && replacement != spec.storageRoot &&
                    inspector.isTreeAvailable(replacement)
                task.id to TaskCapabilities(requiresAccess, replacementReady)
            }
            withContext(Dispatchers.Main) {
                taskCapabilities = capabilities
            }
        }
    }

    companion object {
        private val douyinUrl = Regex(
            "https?://[^\\s]*?(?:douyin\\.com|iesdouyin\\.com)[^\\s]*",
            RegexOption.IGNORE_CASE,
        )

        fun extractDouyinUrl(text: String): String? = douyinUrl.find(text)?.value
    }
}
