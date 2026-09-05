package com.local.douyindownloader

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

sealed interface ParseUiState {
    data object Idle : ParseUiState
    data class LoadingWeb(
        val platform: SourcePlatform,
        val url: String,
        val capturePage: Boolean = false,
    ) : ParseUiState
    data object Parsing : ParseUiState
    data class Ready(val result: ParseResult) : ParseUiState
    data class Error(val code: String, val message: String) : ParseUiState
}

enum class CookieReadySource(val wireValue: String) {
    PAGE_READY("page_ready"),
    PAGE_ERROR("page_error"),
    TIMEOUT("timeout"),
}

internal enum class ParserCredentialMode(val wireValue: String) {
    ANONYMOUS("anonymous"),
    STORED_COOKIE("stored_cookie"),
}

internal fun initialParserCredentialMode(
    platform: SourcePlatform,
    hasStoredCookie: Boolean,
): ParserCredentialMode? = when {
    platform == SourcePlatform.XIAOHONGSHU -> ParserCredentialMode.ANONYMOUS
    hasStoredCookie -> ParserCredentialMode.STORED_COOKIE
    platform.anonymousFirst -> ParserCredentialMode.ANONYMOUS
    else -> null
}

internal fun nextParserCredentialMode(
    platform: SourcePlatform,
    errorCode: String,
    currentMode: ParserCredentialMode,
    hasStoredCookie: Boolean,
    attemptedModes: Set<ParserCredentialMode>,
): ParserCredentialMode? {
    if (platform != SourcePlatform.XIAOHONGSHU ||
        errorCode !in setOf("AUTH_OR_RISK", "DETAIL_EMPTY", "LOGIN_REQUIRED")
    ) {
        return null
    }
    val candidate = when (currentMode) {
        ParserCredentialMode.ANONYMOUS -> ParserCredentialMode.STORED_COOKIE
            .takeIf { hasStoredCookie }
        ParserCredentialMode.STORED_COOKIE -> ParserCredentialMode.ANONYMOUS
    }
    return candidate?.takeUnless(attemptedModes::contains)
}

internal fun shouldRefreshCookieEnvironment(
    errorCode: String,
    refreshAttempted: Boolean,
    platform: SourcePlatform = SourcePlatform.DOUYIN,
    supportsTargetPageSnapshot: Boolean = false,
): Boolean {
    if (refreshAttempted || errorCode !in setOf("AUTH_OR_RISK", "DETAIL_EMPTY", "LOGIN_REQUIRED")) {
        return false
    }
    return platform != SourcePlatform.ZHIHU || supportsTargetPageSnapshot
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
    val isExportingDiagnostics: Boolean = false,
    val message: String = "",
    val preferH264: Boolean = false,
    val customTreeUri: String? = null,
    val platformCredentialStates: Map<SourcePlatform, PlatformCredentialState> =
        SourcePlatform.entries.associateWith { PlatformCredentialState.NOT_DETECTED },
)

@HiltViewModel
class MainViewModel @Inject internal constructor(
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
    private val shareCoordinator: ShareCoordinator,
    private val mediaPreviewCoordinator: MediaPreviewCoordinator,
    private val taskPreviewResolver: TaskPreviewResolver,
    private val taskFolderNavigator: TaskFolderNavigator,
    private val taskFileOperationCoordinator: TaskFileOperationCoordinator,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val _expandedTaskId = MutableStateFlow<String?>(null)
    internal val expandedTaskId: StateFlow<String?> = _expandedTaskId.asStateFlow()
    internal val mediaPreviewState: StateFlow<MediaPreviewState> = mediaPreviewCoordinator.state
    private val _fullscreenTaskId = MutableStateFlow<String?>(null)
    internal val fullscreenTaskId: StateFlow<String?> = _fullscreenTaskId.asStateFlow()
    private val _fileOperationTaskId = MutableStateFlow<String?>(null)
    internal val fileOperationTaskId: StateFlow<String?> = _fileOperationTaskId.asStateFlow()
    private val diagnosticExportChannel = Channel<DiagnosticExportResult>(Channel.BUFFERED)
    internal val diagnosticExports = diagnosticExportChannel.receiveAsFlow()

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
    private var sessionPlatform = SourcePlatform.DOUYIN
    private var sessionSourceUrl = ""
    private var sessionSupportsPageSnapshot = false
    private var sessionStoredCookieHeader = ""
    private val sessionCredentialAttempts = mutableSetOf<ParserCredentialMode>()
    private var parsingStarted = false
    private var environmentRefreshAttempted = false
    private val refreshMutex = Mutex()
    private var taskCapabilities = emptyMap<String, TaskCapabilities>()
    private var tasksVisible = false

    init {
        refreshPlatformCredentialStates()
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
                val expanded = _expandedTaskId.value
                if (expanded != null && records.none { it.id == expanded }) {
                    mediaPreviewCoordinator.stopIfTask(expanded, "TASK_REMOVED")
                    if (_fullscreenTaskId.value == expanded) _fullscreenTaskId.value = null
                    _expandedTaskId.value = null
                }
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
        val source = extractSupportedSource(inputText)
        if (source == null) {
            message = "没有找到抖音、小红书或知乎链接"
            return
        }
        sessionPlatform = source.platform
        sessionSourceUrl = source.url
        sessionSupportsPageSnapshot = when (source.platform) {
            SourcePlatform.XIAOHONGSHU -> true
            SourcePlatform.ZHIHU -> runCatching {
                ZhihuSourceResolver.resolve(source.url).type != ZhihuContentType.VIDEO
            }.getOrDefault(false)
            SourcePlatform.DOUYIN -> false
        }
        sessionId = UUID.randomUUID().toString()
        sessionCredentialAttempts.clear()
        parsingStarted = false
        environmentRefreshAttempted = false
        logger.event(sessionId, "INPUT", "LINK_ACCEPTED", JSONObject().apply {
            put("host", Uri.parse(source.url).host)
            put("platform", source.platform.wireValue)
        })
        val cookieHeader = CookieManager.getInstance()
            .getCookie(source.platform.homeUrl)
            .orEmpty()
        sessionStoredCookieHeader = cookieHeader
        when (val mode = initialParserCredentialMode(source.platform, cookieHeader.isNotBlank())) {
            ParserCredentialMode.ANONYMOUS -> {
                logger.event(sessionId, "COOKIE", "ANONYMOUS_PARSE_STARTED", JSONObject().apply {
                    put("present", false)
                    put("stored_cookie_present", cookieHeader.isNotBlank())
                })
                startParse("")
            }
            ParserCredentialMode.STORED_COOKIE -> {
                logger.event(
                    sessionId,
                    "COOKIE",
                    "COOKIE_REUSED",
                    JSONObject().put("present", true),
                )
                startParse(cookieHeader)
            }
            null -> requestEnvironmentRefresh("cookie_missing")
        }
    }

    fun parseWithCookies(
        cookieHeader: String,
        source: CookieReadySource,
        pageSnapshot: WebPageSnapshot?,
    ) {
        if (parsingStarted) return
        sessionStoredCookieHeader = cookieHeader
        logger.event(sessionId, "COOKIE", "COOKIE_READY", JSONObject().apply {
            put("present", cookieHeader.isNotBlank())
            put("source", source.wireValue)
            put("page_snapshot", pageSnapshot != null)
            put("snapshot_initial_bytes", pageSnapshot?.initialData?.length ?: 0)
            put("snapshot_content_bytes", pageSnapshot?.contentHtml?.length ?: 0)
            put("snapshot_host", pageSnapshot?.finalUrl?.let { Uri.parse(it).host }.orEmpty())
        })
        startParse(cookieHeader, pageSnapshot)
    }

    private fun startParse(cookieHeader: String, pageSnapshot: WebPageSnapshot? = null) {
        if (parsingStarted) return
        val credentialMode = if (cookieHeader.isBlank()) {
            ParserCredentialMode.ANONYMOUS
        } else {
            ParserCredentialMode.STORED_COOKIE
        }
        sessionCredentialAttempts += credentialMode
        parsingStarted = true
        parseState = ParseUiState.Parsing
        viewModelScope.launch {
            val result = parser.parse(inputText, cookieHeader, pageSnapshot)
            result.parserAttempts.forEach { attempt ->
                logger.event(
                    sessionId,
                    "PARSE",
                    if (attempt.selected) "PARSE_STRATEGY_SELECTED" else "PARSE_STRATEGY_FAILED",
                    JSONObject().apply {
                        put("strategy", attempt.strategy)
                        put("status_code", attempt.statusCode)
                        put("error_code", attempt.errorCode)
                    },
                )
            }
            val fallbackMode = nextParserCredentialMode(
                platform = sessionPlatform,
                errorCode = result.errorCode,
                currentMode = credentialMode,
                hasStoredCookie = sessionStoredCookieHeader.isNotBlank(),
                attemptedModes = sessionCredentialAttempts,
            )
            if (fallbackMode != null) {
                logger.event(sessionId, "COOKIE", "CREDENTIAL_FALLBACK_STARTED", JSONObject().apply {
                    put("code", result.errorCode)
                    put("from", credentialMode.wireValue)
                    put("to", fallbackMode.wireValue)
                })
                parsingStarted = false
                startParse(
                    if (fallbackMode == ParserCredentialMode.STORED_COOKIE) {
                        sessionStoredCookieHeader
                    } else {
                        ""
                    },
                )
                refreshLogs()
                return@launch
            }
            if (shouldRefreshCookieEnvironment(
                    result.errorCode,
                    environmentRefreshAttempted,
                    sessionPlatform,
                    sessionSupportsPageSnapshot,
                )
            ) {
                logger.event(sessionId, "COOKIE", "COOKIE_REFRESH_REQUIRED", JSONObject().apply {
                    put("code", result.errorCode)
                })
                requestEnvironmentRefresh(result.errorCode.lowercase())
                refreshLogs()
                return@launch
            }
            if (result.ok) {
                logger.event(sessionId, "PARSE", "DETAIL_PARSED", JSONObject().apply {
                    put("content_id", result.contentId)
                    put("platform", result.platform.wireValue)
                    put("kind", result.kind.wireValue)
                    put("variants", result.variants.size)
                    put("images", result.imageUrls.size)
                    put("document_assets", result.document?.assets?.size ?: 0)
                    put("separate_audio", result.audioUrls.isNotEmpty())
                    put("author_present", result.author.isNotBlank())
                    put("author_account_id_present", result.authorAccountId.isNotBlank())
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
        environmentRefreshAttempted = false
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
            try {
                store.insert(spec)
                runCatching {
                    logger.event(id, "QUALITY", "DOWNLOAD_CONFIRMED", JSONObject().apply {
                        put("variant", selectedVariant)
                        put("mode", selectedMode.wireValue)
                    })
                }
                scheduler.enqueue(id, ExistingWorkPolicy.KEEP)
                message = "已加入后台下载"
                resetParse()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val safeMessage = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                runCatching {
                    store.update(id, TaskStatus.FAILED, "启动下载失败", 0, safeMessage)
                }
                runCatching {
                    logger.event(id, "SCHEDULE", "TASK_SUBMISSION_FAILED", JSONObject().apply {
                        put("type", error.javaClass.name)
                        put("message", safeMessage)
                    })
                }
                message = "创建下载任务失败：$safeMessage"
            }
        }
    }

    fun cancelTask(task: TaskRecord) {
        viewModelScope.launch {
            runCatching { logger.event(task.id, "DOWNLOAD", "CANCEL_REQUESTED") }
            try {
                scheduler.cancel(task.id)
                store.update(task.id, TaskStatus.CANCELLED, "已取消", task.progress)
                runCatching { logger.event(task.id, "DOWNLOAD", "CANCEL_ACCEPTED") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val safeMessage = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                runCatching {
                    logger.event(task.id, "DOWNLOAD", "CANCEL_FAILED", JSONObject().apply {
                        put("type", error.javaClass.name)
                        put("message", safeMessage)
                    })
                }
                message = "取消失败：$safeMessage"
            }
        }
    }

    fun retryTask(task: TaskRecord) {
        val cookieHeader = CookieManager.getInstance()
            .getCookie(task.platform.homeUrl).orEmpty()
        viewModelScope.launch {
            message = redownloadCoordinator.retry(task, cookieHeader, customTreeUri).message
            refreshTasks()
        }
    }

    fun retryTasks(tasks: List<TaskRecord>) {
        val uniqueTasks = tasks.distinctBy(TaskRecord::id)
        if (uniqueTasks.isEmpty()) return
        viewModelScope.launch {
            var started = 0
            var failed = 0
            var skipped = 0
            uniqueTasks.forEach { task ->
                if (!isTaskRedownloadEligible(task)) {
                    skipped += 1
                    return@forEach
                }
                val cookieHeader = CookieManager.getInstance()
                    .getCookie(task.platform.homeUrl).orEmpty()
                val result = runCatching {
                    redownloadCoordinator.retry(task, cookieHeader, customTreeUri)
                }.getOrElse { error ->
                    runCatching {
                        logger.event(task.id, "REDOWNLOAD", "BATCH_REDOWNLOAD_FAILED", JSONObject().apply {
                            put("type", error.javaClass.name)
                            put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
                        })
                    }
                    TaskRedownloadResult(false, "重新下载失败")
                }
                if (result.success) started += 1 else failed += 1
            }
            message = batchRedownloadSummary(started, failed, skipped)
            refreshTasks()
        }
    }

    internal fun shareTaskFiles(context: Context, taskId: String, files: List<ShareableFile>) {
        message = "正在准备分享…"
        viewModelScope.launch {
            val result = shareCoordinator.share(context, taskId, files)
            if (result.message.isNotBlank()) message = result.message
        }
    }

    internal fun toggleTaskPreview(taskId: String) {
        val current = _expandedTaskId.value
        if (current == taskId) {
            if (_fullscreenTaskId.value == taskId) _fullscreenTaskId.value = null
            mediaPreviewCoordinator.stopIfTask(taskId)
            _expandedTaskId.value = null
        } else {
            _fullscreenTaskId.value = null
            mediaPreviewCoordinator.stopAndRelease("PREVIEW_SWITCHED")
            _expandedTaskId.value = taskId
        }
    }

    internal suspend fun resolveTaskPreview(
        taskId: String,
        outputs: List<TaskOutput>,
    ): List<TaskPreviewMedia> = taskPreviewResolver.resolve(taskId, outputs)

    internal fun toggleMediaPreview(taskId: String, media: TaskPreviewMedia) {
        if (_expandedTaskId.value != taskId) _expandedTaskId.value = taskId
        mediaPreviewCoordinator.toggle(taskId, media)
    }

    internal fun seekMediaPreview(taskId: String, positionMs: Long) {
        mediaPreviewCoordinator.seekTo(taskId, positionMs)
    }

    internal fun toggleMediaMute(taskId: String) {
        mediaPreviewCoordinator.toggleMute(taskId)
    }

    internal fun enterFullscreen(taskId: String, media: TaskPreviewMedia) {
        if (mediaPreviewState.value.taskId != taskId ||
            !mediaPreviewState.value.source.samePreviewSource(media)
        ) {
            mediaPreviewCoordinator.toggle(taskId, media)
        }
        _fullscreenTaskId.value = taskId
    }

    internal fun exitFullscreen() {
        if (_fullscreenTaskId.value == null) return
        mediaPreviewCoordinator.pause("FULLSCREEN_EXITED")
        _fullscreenTaskId.value = null
    }

    internal fun stopMediaPreviewIfTask(taskId: String, reason: String = "PREVIEW_DISPOSED") {
        if (_fullscreenTaskId.value != taskId) {
            mediaPreviewCoordinator.stopIfTask(taskId, reason)
        }
    }

    internal fun openTaskFolder(context: Context, task: TaskRecord) {
        viewModelScope.launch {
            val spec = store.getSpec(task.id)
            if (spec == null) {
                message = "无法读取该任务的保存目录"
                return@launch
            }
            val result = taskFolderNavigator.open(context, task.id, spec)
            if (result.message.isNotBlank()) message = result.message
        }
    }

    internal fun onFileManagerOpened(taskId: String) {
        mediaPreviewCoordinator.stopIfTask(taskId, "FILE_MANAGER_OPENED")
        runCatching {
            logger.event(taskId, "FILE_MANAGER", "FILE_MANAGER_OPENED")
        }
    }

    internal suspend fun describeManagedFiles(outputs: List<TaskOutput>): List<ManagedFileItem> =
        taskFileOperationCoordinator.describe(outputs)

    internal suspend fun loadDocumentReader(task: TaskRecord): DocumentReaderData? =
        withContext(Dispatchers.IO) {
            val document = store.getSpec(task.id)?.result?.document ?: return@withContext null
            val availableOutputs = task.outputs.filter { inspector.outputExists(it.uri) }
            val assetOutputs = resolveDocumentAssetOutputs(document, availableOutputs)
            runCatching {
                logger.event(task.id, "DOCUMENT_READER", "DOCUMENT_OPENED", JSONObject().apply {
                    put("assets", document.assets.size)
                    put("local_assets", assetOutputs.size)
                    put("missing_assets", document.assets.size - assetOutputs.size)
                })
            }
            DocumentReaderData(document, assetOutputs)
        }

    internal fun openDocumentMedia(context: Context, taskId: String, output: TaskOutput) {
        val uri = runCatching { Uri.parse(output.uri) }.getOrNull()
        if (uri == null || !inspector.outputExists(output.uri)) {
            message = "文件已被删除"
            return
        }
        openOutput(context, taskId, uri, output.displayName, output.mimeType)
    }

    internal fun openManagedFile(context: Context, taskId: String, item: ManagedFileItem) {
        if (!item.available) {
            message = "文件已被删除"
            return
        }
        openOutput(
            context,
            taskId,
            item.uri,
            item.output.displayName,
            item.output.mimeType,
        )
    }

    private fun openOutput(
        context: Context,
        taskId: String,
        uri: Uri,
        displayName: String,
        providerType: String,
    ) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                mediaMimeType(displayName, providerType),
            )
            clipData = ClipData.newRawUri("下载文件", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { error ->
                runCatching {
                    logger.event(taskId, "FILE_MANAGER", "FILE_OPEN_FAILED", JSONObject().apply {
                        put("authority", uri.authority.orEmpty())
                        put("error", error.javaClass.name)
                        put("message", Redactor.sanitize(error.message.orEmpty()))
                    })
                }
                message = "没有可打开该文件的应用"
            }
    }

    internal fun renameManagedFile(taskId: String, outputUri: String, requestedBase: String) {
        launchFileOperation(taskId) {
            taskFileOperationCoordinator.rename(taskId, outputUri, requestedBase)
        }
    }

    internal fun deleteManagedFiles(taskId: String, outputUris: Set<String>) {
        launchFileOperation(taskId) {
            taskFileOperationCoordinator.delete(taskId, outputUris)
        }
    }

    internal fun transferManagedFiles(
        taskId: String,
        outputUris: Set<String>,
        destinationTree: Uri,
        mode: ManagedTransferMode,
    ) {
        launchFileOperation(taskId) {
            taskFileOperationCoordinator.transfer(taskId, outputUris, destinationTree, mode)
        }
    }

    internal fun showMessage(value: String) {
        message = value
    }

    private fun launchFileOperation(
        taskId: String,
        operation: suspend () -> ManagedFileOperationResult,
    ) {
        if (_fileOperationTaskId.value != null) {
            message = "另一个文件操作正在进行"
            return
        }
        _fileOperationTaskId.value = taskId
        mediaPreviewCoordinator.stopIfTask(taskId, "FILE_OPERATION_REQUESTED")
        viewModelScope.launch {
            try {
                message = operation().message
                refreshTasks()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                runCatching {
                    logger.event(taskId, "FILE_MANAGER", "FILE_OPERATION_FAILED", JSONObject().apply {
                        put("error", error.javaClass.name)
                        put("message", detail)
                    })
                }
                message = "文件操作失败：$detail"
            } finally {
                _fileOperationTaskId.value = null
            }
        }
    }

    fun deleteTask(task: TaskRecord, deleteFiles: Boolean) {
        if (_expandedTaskId.value == task.id) {
            if (_fullscreenTaskId.value == task.id) _fullscreenTaskId.value = null
            mediaPreviewCoordinator.stopIfTask(task.id, "TASK_DELETE_REQUESTED")
            _expandedTaskId.value = null
        }
        viewModelScope.launch {
            val result = deletionCoordinator.deleteTask(task.id, deleteFiles)
            message = result.message
            refreshTasks()
        }
    }

    fun deleteTasks(tasks: List<TaskRecord>, deleteFiles: Boolean) {
        val uniqueTasks = tasks.distinctBy(TaskRecord::id)
        if (uniqueTasks.isEmpty()) return
        uniqueTasks.forEach { task ->
            if (_expandedTaskId.value == task.id) {
                if (_fullscreenTaskId.value == task.id) _fullscreenTaskId.value = null
                mediaPreviewCoordinator.stopIfTask(task.id, "BATCH_DELETE_REQUESTED")
                _expandedTaskId.value = null
            }
        }
        viewModelScope.launch {
            val results = uniqueTasks.map { task ->
                runCatching { deletionCoordinator.deleteTask(task.id, deleteFiles) }
                    .getOrElse { error ->
                        logger.event(task.id, "DELETE", "BATCH_DELETE_FAILED", JSONObject().apply {
                            put("type", error.javaClass.name)
                            put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
                        })
                        TaskDeleteResult(false, "删除失败")
                    }
            }
            val deleted = results.count(TaskDeleteResult::success)
            val failed = results.size - deleted
            message = when {
                failed == 0 -> "已删除 $deleted 个任务"
                deleted == 0 -> "所选任务均删除失败，请查看任务状态或诊断日志"
                else -> "已删除 $deleted 个任务，$failed 个任务删除失败"
            }
            refreshTasks()
        }
    }

    internal fun onDocumentImageLoadFailed(
        taskId: String,
        assetId: String,
        outputName: String,
        sourceScheme: String,
        error: Throwable,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                logger.event(taskId, "DOCUMENT_READER", "IMAGE_LOAD_FAILED", JSONObject().apply {
                    put("asset_id", assetId)
                    put("output_name", outputName)
                    put("source_scheme", sourceScheme)
                    put("type", error.javaClass.name)
                    put("message", Redactor.sanitize(error.message ?: error.javaClass.simpleName))
                })
            }
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

    fun onAppForeground() {
        refreshPlatformCredentialStates()
        refreshTasks()
    }

    fun onLoginEnvironmentOpened(platform: SourcePlatform) {
        refreshPlatformCredentialStates()
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_ENVIRONMENT_OPENED", JSONObject().apply {
            put("platform", platform.wireValue)
            put(
                "credential_detected",
                _uiState.value.platformCredentialStates[platform] == PlatformCredentialState.DETECTED,
            )
        })
        refreshLogs()
    }

    fun onLoginEnvironmentClosed(platform: SourcePlatform) {
        CookieManager.getInstance().flush()
        refreshPlatformCredentialStates()
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_ENVIRONMENT_CLOSED", JSONObject().apply {
            put("platform", platform.wireValue)
            put(
                "credential_detected",
                _uiState.value.platformCredentialStates[platform] == PlatformCredentialState.DETECTED,
            )
        })
        refreshLogs()
    }

    fun onLoginPageFinished(platform: SourcePlatform, url: String) {
        refreshPlatformCredentialStates()
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_PAGE_FINISHED", JSONObject().apply {
            put("platform", platform.wireValue)
            put("host", runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
            put(
                "credential_detected",
                _uiState.value.platformCredentialStates[platform] == PlatformCredentialState.DETECTED,
            )
        })
    }

    fun onLoginPageError(
        platform: SourcePlatform,
        url: String,
        errorCode: Int,
        description: String,
    ) {
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_PAGE_ERROR", JSONObject().apply {
            put("platform", platform.wireValue)
            put("host", runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
            put("error_code", errorCode)
            put("description", Redactor.sanitize(description))
        })
        refreshLogs()
    }

    fun onAppBackground() {
        _fullscreenTaskId.value = null
        mediaPreviewCoordinator.stopAndRelease("APP_BACKGROUNDED")
    }

    fun onTasksVisible() {
        tasksVisible = true
        refreshTasks()
    }

    fun onTasksHidden() {
        tasksVisible = false
        _fullscreenTaskId.value = null
        mediaPreviewCoordinator.stopAndRelease()
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

    private fun refreshPlatformCredentialStates() {
        val cookieManager = CookieManager.getInstance()
        val states = SourcePlatform.entries.associateWith { platform ->
            detectPlatformCredential(platform, cookieManager.getCookie(platform.homeUrl).orEmpty())
        }
        _uiState.update { it.copy(platformCredentialStates = states) }
    }

    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val text = logger.readRecent()
            withContext(Dispatchers.Main) { logText = text }
        }
    }

    fun exportLogs() {
        if (_uiState.value.isExportingDiagnostics) return
        _uiState.update { it.copy(isExportingDiagnostics = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val export = logger.export()
                diagnosticExportChannel.send(export)
                withContext(Dispatchers.Main) {
                    message = "诊断包已保存：${export.relativePath}/${export.displayName}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                withContext(Dispatchers.Main) { message = "导出失败：$detail" }
            } finally {
                _uiState.update { it.copy(isExportingDiagnostics = false) }
            }
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

    override fun onCleared() {
        mediaPreviewCoordinator.stopAndRelease("VIEW_MODEL_CLEARED")
        super.onCleared()
    }

    private fun preferredVariant(result: ParseResult): Int {
        if (!preferH264 || result.variants.isEmpty()) return 0
        val highest = result.variants.first()
        return result.variants.indexOfFirst {
            it.width == highest.width && it.height == highest.height && it.codec.contains("264")
        }.takeIf { it >= 0 } ?: 0
    }

    private fun requestEnvironmentRefresh(reason: String) {
        environmentRefreshAttempted = true
        parsingStarted = false
        logger.event(sessionId, "COOKIE", "COOKIE_WARMUP_STARTED", JSONObject().put("reason", reason))
        parseState = ParseUiState.LoadingWeb(
            platform = sessionPlatform,
            url = if (sessionSupportsPageSnapshot) sessionSourceUrl else sessionPlatform.homeUrl,
            capturePage = sessionSupportsPageSnapshot,
        )
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

}

private fun TaskPreviewMedia?.samePreviewSource(other: TaskPreviewMedia): Boolean =
    this?.uri == other.uri
