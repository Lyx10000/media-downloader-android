package com.local.multiplatformdownloader.feature.home


import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.feature.download.DownloadScheduler
import com.local.multiplatformdownloader.core.download.mediaMimeType
import com.local.multiplatformdownloader.core.download.TrackDownloadProgressRegistry
import com.local.multiplatformdownloader.core.download.AdaptiveDownloadController
import com.local.multiplatformdownloader.core.logging.DiagnosticExportResult
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.model.AttachmentSelection
import com.local.multiplatformdownloader.core.model.DownloadMode
import com.local.multiplatformdownloader.core.model.MediaAttachmentKind
import com.local.multiplatformdownloader.core.model.MediaVariant
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.StorageMode
import com.local.multiplatformdownloader.core.model.TaskOutput
import com.local.multiplatformdownloader.core.model.TaskRecord
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.core.model.TaskStatus
import com.local.multiplatformdownloader.core.model.UpdateSource
import com.local.multiplatformdownloader.core.model.UpdateUiState
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.model.extractSupportedSource
import com.local.multiplatformdownloader.core.model.taskFolderName
import com.local.multiplatformdownloader.core.storage.StorageInspector
import com.local.multiplatformdownloader.core.update.UpdateLaunchRequest
import com.local.multiplatformdownloader.core.update.UpdateRepository
import com.local.multiplatformdownloader.feature.creator.CreatorLibraryRepository
import com.local.multiplatformdownloader.feature.creator.CreatorWork
import com.local.multiplatformdownloader.feature.creator.creatorKey
import com.local.multiplatformdownloader.feature.creator.creatorWorkFolder
import com.local.multiplatformdownloader.feature.creator.creatorWorkKey
import com.local.multiplatformdownloader.feature.creator.taskCreatorKey
import com.local.multiplatformdownloader.feature.document.DocumentReaderData
import com.local.multiplatformdownloader.feature.document.resolveDocumentAssetOutputs
import com.local.multiplatformdownloader.feature.preview.MediaPreviewCoordinator
import com.local.multiplatformdownloader.feature.preview.MediaPreviewState
import com.local.multiplatformdownloader.core.settings.SettingsRepository
import com.local.multiplatformdownloader.feature.tasks.ManagedFileItem
import com.local.multiplatformdownloader.feature.tasks.ManagedFileOperationResult
import com.local.multiplatformdownloader.feature.tasks.ManagedTransferMode
import com.local.multiplatformdownloader.feature.tasks.ShareCoordinator
import com.local.multiplatformdownloader.feature.tasks.ShareableFile
import com.local.multiplatformdownloader.feature.tasks.TaskDeleteResult
import com.local.multiplatformdownloader.feature.tasks.TaskDeletionCoordinator
import com.local.multiplatformdownloader.feature.tasks.TaskFileOperationCoordinator
import com.local.multiplatformdownloader.feature.tasks.TaskFileStateRefresher
import com.local.multiplatformdownloader.feature.tasks.TaskFolderNavigator
import com.local.multiplatformdownloader.feature.tasks.TaskPreviewMedia
import com.local.multiplatformdownloader.feature.tasks.TaskPreviewResolver
import com.local.multiplatformdownloader.feature.tasks.TaskRedownloadCoordinator
import com.local.multiplatformdownloader.feature.tasks.TaskRedownloadResult
import com.local.multiplatformdownloader.feature.tasks.batchRedownloadSummary
import com.local.multiplatformdownloader.feature.tasks.isTaskRedownloadEligible
import com.local.multiplatformdownloader.feature.tasks.isTaskQueueVisible
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchive
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveCoordinator
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionDownloadScope
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionRepository
import com.local.multiplatformdownloader.platform.bilibili.BilibiliPlatformParser
import com.local.multiplatformdownloader.platform.bilibili.bilibiliPartResult
import com.local.multiplatformdownloader.platform.common.ParserGateway
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
import com.local.multiplatformdownloader.platform.common.XiaohongshuCredentialValidationCache
import com.local.multiplatformdownloader.platform.common.classifyXiaohongshuCredentialSnapshot
import com.local.multiplatformdownloader.platform.common.detectPlatformCredential
import com.local.multiplatformdownloader.core.network.responseShape
import com.local.multiplatformdownloader.platform.xiaohongshu.XiaohongshuMediaParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuContentType
import com.local.multiplatformdownloader.platform.zhihu.ZhihuSourceResolver

import com.local.multiplatformdownloader.R

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
import kotlinx.coroutines.Job
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
    platform in setOf(SourcePlatform.XIAOHONGSHU, SourcePlatform.X) ->
        ParserCredentialMode.ANONYMOUS
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
    if (platform !in setOf(SourcePlatform.XIAOHONGSHU, SourcePlatform.X, SourcePlatform.INSTAGRAM) ||
        !isRecoverableParseError(platform, errorCode)
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
    if (refreshAttempted || !isRecoverableParseError(platform, errorCode)) {
        return false
    }
    return platform != SourcePlatform.BILIBILI &&
        (platform != SourcePlatform.ZHIHU || supportsTargetPageSnapshot)
}

private fun isRecoverableParseError(platform: SourcePlatform, errorCode: String): Boolean =
    errorCode in COMMON_RECOVERABLE_PARSE_ERRORS ||
        (platform == SourcePlatform.XIAOHONGSHU && errorCode == "URL_RESOLVE_FAILED")

private val COMMON_RECOVERABLE_PARSE_ERRORS = setOf(
    "AUTH_OR_RISK",
    "DETAIL_EMPTY",
    "LOGIN_REQUIRED",
)

private const val MAX_MARKDOWN_PREVIEW_CHARS = 2_000_000

private data class TaskCapabilities(
    val requiresAllFilesAccess: Boolean = false,
    val hasReplacementStorage: Boolean = false,
)

data class MainUiState(
    val inputText: String = "",
    val parseState: ParseUiState = ParseUiState.Idle,
    val selectedVariant: Int = 0,
    val selectedAttachmentVariants: Map<String, Int> = emptyMap(),
    val selectedBilibiliCids: Set<String> = emptySet(),
    val selectedMode: DownloadMode = DownloadMode.MERGE_KEEP,
    val tasks: List<TaskRecord> = emptyList(),
    val allTasks: List<TaskRecord> = emptyList(),
    val logText: String = "",
    val isExportingDiagnostics: Boolean = false,
    val message: String = "",
    val preferH264: Boolean = false,
    val customTreeUri: String? = null,
    val platformCredentialStates: Map<SourcePlatform, PlatformCredentialState> =
        SourcePlatform.entries.associateWith { PlatformCredentialState.NOT_DETECTED },
    val updateState: UpdateUiState = UpdateUiState(),
    val questionArchives: Map<String, ZhihuQuestionArchive> = emptyMap(),
)

@HiltViewModel
class MainViewModel @Inject internal constructor(
    application: Application,
    private val parser: ParserGateway,
    private val bilibiliParser: BilibiliPlatformParser,
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
    private val updateRepository: UpdateRepository,
    private val creatorRepository: CreatorLibraryRepository,
    private val zhihuQuestionArchiveCoordinator: ZhihuQuestionArchiveCoordinator,
    private val zhihuQuestionRepository: ZhihuQuestionRepository,
    trackDownloadProgressRegistry: TrackDownloadProgressRegistry,
    private val adaptiveDownloadController: AdaptiveDownloadController,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    private var bilibiliCredentialJob: Job? = null
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val _expandedTaskId = MutableStateFlow<String?>(null)
    internal val expandedTaskId: StateFlow<String?> = _expandedTaskId.asStateFlow()
    internal val mediaPreviewState: StateFlow<MediaPreviewState> = mediaPreviewCoordinator.state
    private val _fullscreenTaskId = MutableStateFlow<String?>(null)
    internal val fullscreenTaskId: StateFlow<String?> = _fullscreenTaskId.asStateFlow()
    internal val trackDownloadProgress = trackDownloadProgressRegistry.state
    internal val adaptiveDownloadState = adaptiveDownloadController.state
    private val _authorTaskPeakBytesPerSecond = MutableStateFlow<Map<String, Long>>(emptyMap())
    internal val authorTaskPeakBytesPerSecond: StateFlow<Map<String, Long>> =
        _authorTaskPeakBytesPerSecond.asStateFlow()
    private val _fileOperationTaskId = MutableStateFlow<String?>(null)
    internal val fileOperationTaskId: StateFlow<String?> = _fileOperationTaskId.asStateFlow()
    private val diagnosticExportChannel = Channel<DiagnosticExportResult>(Channel.BUFFERED)
    internal val diagnosticExports = diagnosticExportChannel.receiveAsFlow()
    private val updateLaunchChannel = Channel<UpdateLaunchRequest>(Channel.BUFFERED)
    internal val updateLaunchRequests = updateLaunchChannel.receiveAsFlow()
    private val completedTaskChannel = Channel<String>(Channel.BUFFERED)
    internal val completedTasks = completedTaskChannel.receiveAsFlow()
    private var updateDownloadJob: Job? = null

    var inputText: String
        get() = _uiState.value.inputText
        set(value) = _uiState.update { it.copy(inputText = value) }
    var parseState: ParseUiState
        get() = _uiState.value.parseState
        private set(value) = _uiState.update { it.copy(parseState = value) }
    var selectedVariant: Int
        get() = _uiState.value.selectedVariant
        set(value) = _uiState.update { it.copy(selectedVariant = value) }
    var selectedAttachmentVariants: Map<String, Int>
        get() = _uiState.value.selectedAttachmentVariants
        private set(value) = _uiState.update { it.copy(selectedAttachmentVariants = value) }
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
    private val xiaohongshuCredentialCache = XiaohongshuCredentialValidationCache()
    private val refreshMutex = Mutex()
    private var taskCapabilities = emptyMap<String, TaskCapabilities>()
    private var tasksVisible = false
    private var observedTaskStatuses: Map<String, TaskStatus>? = null

    init {
        refreshPlatformCredentialStates()
        refreshLogs()
        viewModelScope.launch {
            updateRepository.state.collectLatest { state ->
                _uiState.update { it.copy(updateState = state) }
            }
        }
        viewModelScope.launch { updateRepository.check(manual = false) }
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                selectedMode = settings.defaultMode
                preferH264 = settings.preferH264
                customTreeUri = settings.customTreeUri
                refreshTaskMetadata(tasks)
            }
        }
        viewModelScope.launch {
            var previouslyBlocked = emptySet<SourcePlatform>()
            adaptiveDownloadController.state.collectLatest { state ->
                val now = System.currentTimeMillis()
                val blocked = state.platformRiskUntil.filterValues { it > now }.keys
                val newlyBlocked = blocked - previouslyBlocked
                if (newlyBlocked.isNotEmpty()) {
                    message = newlyBlocked.joinToString("、") { it.displayName } +
                        "请求受限，已暂停该平台的新任务；正在下载的内容会继续完成"
                }
                previouslyBlocked = blocked
                updateAuthorTaskPeaks(state.taskBytesPerSecond)
            }
        }
        viewModelScope.launch {
            store.observeAll().collectLatest { records ->
                observedTaskStatuses?.let { previous ->
                    records.filter { task ->
                        task.status == TaskStatus.COMPLETE && previous[task.id] != TaskStatus.COMPLETE
                    }.forEach { completedTaskChannel.trySend(it.id) }
                }
                observedTaskStatuses = records.associate { it.id to it.status }
                tasks = records.filterNot(TaskRecord::creatorChild)
                _uiState.update { state -> state.copy(allTasks = records) }
                updateAuthorTaskPeaks(adaptiveDownloadController.state.value.taskBytesPerSecond)
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
            zhihuQuestionRepository.observeQuestions().collectLatest { archives ->
                _uiState.update { state ->
                    state.copy(questionArchives = archives.associateBy(ZhihuQuestionArchive::parentTaskId))
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (tasksVisible && _uiState.value.allTasks.isNotEmpty()) refreshTasks()
            }
        }
    }

    private fun updateAuthorTaskPeaks(taskSpeeds: Map<String, Long>) {
        val activeByAuthor = _uiState.value.allTasks.asSequence()
            .filter { taskCreatorKey(it).isNotBlank() && isTaskQueueVisible(it) }
            .groupBy(::taskCreatorKey)
        _authorTaskPeakBytesPerSecond.update { previous ->
            activeByAuthor.mapValues { (authorKey, tasks) ->
                maxOf(previous[authorKey] ?: 0L, tasks.sumOf { taskSpeeds[it.id] ?: 0L })
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
            message = "没有找到抖音、小红书、知乎、X、Instagram 或 B站链接"
            return
        }
        sessionPlatform = source.platform
        sessionSourceUrl = source.url
        sessionSupportsPageSnapshot = when (source.platform) {
            SourcePlatform.XIAOHONGSHU -> true
            SourcePlatform.ZHIHU -> runCatching {
                ZhihuSourceResolver.resolve(source.url).type != ZhihuContentType.VIDEO
            }.getOrDefault(false)
            SourcePlatform.DOUYIN, SourcePlatform.BILIBILI -> false
            SourcePlatform.X, SourcePlatform.INSTAGRAM -> true
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
            put("snapshot_path", pageSnapshot?.finalUrl?.let { Uri.parse(it).path }.orEmpty())
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
            if (
                !result.ok && sessionPlatform == SourcePlatform.XIAOHONGSHU &&
                result.canonicalUrl.isNotBlank() &&
                XiaohongshuMediaParser.noteIdFromUrl(result.canonicalUrl).isNotBlank()
            ) {
                sessionSourceUrl = result.canonicalUrl
                logger.event(sessionId, "PARSE", "RESOLVED_TARGET_RETAINED", JSONObject().apply {
                    put("host", runCatching { Uri.parse(result.canonicalUrl).host }.getOrDefault(""))
                    put("path", runCatching { Uri.parse(result.canonicalUrl).path }.getOrDefault(""))
                    put("content_id", result.contentId)
                })
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
                adaptiveDownloadController.reportPlatformSuccess(result.platform)
                logger.event(sessionId, "PARSE", "DETAIL_PARSED", JSONObject().apply {
                    put("content_id", result.contentId)
                    put("platform", result.platform.wireValue)
                    put("kind", result.kind.wireValue)
                    put("variants", result.variants.size)
                    put("images", result.imageUrls.size)
                    put("document_assets", result.document?.assets?.size ?: 0)
                    put("attachments", result.attachments.size)
                    put("separate_audio", result.audioUrls.isNotEmpty())
                    put("author_present", result.author.isNotBlank())
                    put("author_account_id_present", result.authorAccountId.isNotBlank())
                })
                logger.saveResponseShape(sessionId, result.responseShape)
                selectedVariant = preferredVariant(result)
                _uiState.update { it.copy(selectedBilibiliCids = setOf(result.contentId.substringAfter(':', ""))) }
                selectedAttachmentVariants = result.attachments
                    .filter { it.kind != MediaAttachmentKind.IMAGE }
                    .associate { attachment ->
                        attachment.id to preferredVariant(attachment.variants)
                    }
                parseState = ParseUiState.Ready(result)
            } else {
                adaptiveDownloadController.reportPlatformRisk(
                    sessionPlatform,
                    result.errorCode,
                    result.parserAttempts.map { it.statusCode },
                )
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

    fun selectAttachmentVariant(attachmentId: String, index: Int) {
        selectedAttachmentVariants = selectedAttachmentVariants.toMutableMap().apply {
            put(attachmentId, index)
        }
    }

    fun selectMode(mode: DownloadMode) {
        selectedMode = mode
    }

    fun selectBilibiliParts(cids: Set<String>) {
        _uiState.update { it.copy(selectedBilibiliCids = cids) }
    }

    fun queueDownload(result: ParseResult, archiveAuthor: Boolean = false): String {
        val id = sessionId.ifBlank { UUID.randomUUID().toString() }
        val createdAt = System.currentTimeMillis()
        val storageMode = if (customTreeUri.isNullOrBlank()) StorageMode.DEFAULT else StorageMode.SAF
        val requestedCids = _uiState.value.selectedBilibiliCids.toSet()
        val requestedAuthorKey = result.authorStableId.takeIf(String::isNotBlank)
            ?.let { creatorKey(result.platform, it) }.orEmpty()
        val authorBusy = requestedAuthorKey.isNotBlank() && _uiState.value.allTasks.any { task ->
            taskCreatorKey(task) == requestedAuthorKey && isTaskQueueVisible(task)
        }
        val shouldArchiveAuthor = archiveAuthor && !authorBusy
        if (archiveAuthor && authorBusy) {
            message = "该作者有任务正在下载，本次沿用现有归类；完成或取消后才能收藏"
        }
        viewModelScope.launch {
            try {
                val authorKey = creatorRepository.upsertFromParse(
                    result = result,
                    createAuthorIfMissing = shouldArchiveAuthor,
                )
                val taskFolder = if (authorKey.isBlank()) {
                    "独立作品/${result.platform.displayName}/${taskFolderName(createdAt, id)}"
                } else {
                    creatorRepository.getCreator(authorKey)?.let { profile ->
                        creatorWorkFolder(
                            profile,
                            CreatorWork(
                                key = creatorWorkKey(result.platform, result.contentId),
                                creatorKey = authorKey,
                                platform = result.platform,
                                contentId = result.contentId,
                                canonicalUrl = result.canonicalUrl,
                                kind = result.kind,
                                title = result.description,
                                publishedAt = result.publishedAt,
                            ),
                            createdAt,
                        )
                    } ?: taskFolderName(createdAt, id)
                }
                val spec = TaskSpec(
                    taskId = id,
                    createdAt = createdAt,
                    result = result,
                    variantIndex = selectedVariant,
                    attachmentSelections = result.attachments
                        .filter { it.kind != MediaAttachmentKind.IMAGE }
                        .map { attachment ->
                            AttachmentSelection(
                                attachmentId = attachment.id,
                                variantIndex = selectedAttachmentVariants[attachment.id] ?: 0,
                            )
                        },
                    mode = selectedMode,
                    sourceText = inputText,
                    storageMode = storageMode,
                    storageRoot = customTreeUri.orEmpty(),
                    taskFolder = taskFolder,
                    authorKey = authorKey,
                )
                val selectedParts = if (result.platform == SourcePlatform.BILIBILI && result.bilibiliParts.size > 1) {
                    result.bilibiliParts.filter { it.cid in requestedCids }
                } else emptyList()
                check(result.bilibiliParts.size <= 1 || result.platform != SourcePlatform.BILIBILI || selectedParts.isNotEmpty()) { "请至少选择一个分P" }
                val specs = if (selectedParts.isEmpty()) listOf(spec) else selectedParts.mapIndexed { index, part ->
                    val partResult = bilibiliPartResult(result, part)
                    val partId = if (index == 0) id else UUID.randomUUID().toString()
                    spec.copy(taskId = partId, result = partResult, sourceText = partResult.canonicalUrl,
                        bilibiliPending = partResult.variants.all { it.urls.isEmpty() },
                        taskFolder = "$taskFolder/P${part.page}_${part.cid}_${partId.take(8)}")
                }
                var submissionFailures = 0
                specs.forEach { child ->
                    try {
                        store.insert(child)
                        scheduler.enqueue(child.taskId, ExistingWorkPolicy.KEEP)
                    } catch (cancelled: CancellationException) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            store.update(child.taskId, TaskStatus.FAILED, "提交已中断，可手动重试", 0)
                        }
                        throw cancelled
                    } catch (error: Throwable) {
                        submissionFailures++
                        val hint = Redactor.sanitize(error.message ?: "启动下载失败")
                        runCatching { store.update(child.taskId, TaskStatus.FAILED, "启动下载失败", 0, hint) }
                        logger.event(child.taskId, "SCHEDULE", "TASK_SUBMISSION_FAILED", JSONObject().put("message", hint))
                    }
                }
                runCatching {
                    logger.event(id, "QUALITY", "DOWNLOAD_CONFIRMED", JSONObject().apply {
                        put("variant", selectedVariant)
                        put("attachment_variants", selectedAttachmentVariants.size)
                        put("mode", selectedMode.wireValue)
                    })
                }
                message = if (submissionFailures == 0) "已加入后台下载" else "${submissionFailures} 个任务提交失败，可在任务页重试"
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
        return id
    }

    fun queueQuestionArchive(
        result: ParseResult,
        scope: ZhihuQuestionDownloadScope,
        includeComments: Boolean,
    ): String {
        val id = sessionId.ifBlank { UUID.randomUUID().toString() }
        viewModelScope.launch {
            try {
                zhihuQuestionArchiveCoordinator.start(id, result, scope, includeComments)
                message = if (scope == ZhihuQuestionDownloadScope.ALL) {
                    "已开始归档当前账号可见的全部回答"
                } else {
                    "已开始归档第一页回答"
                }
                resetParse()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val safeMessage = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                runCatching {
                    store.update(id, TaskStatus.FAILED, "启动问题归档失败", 0, safeMessage)
                }
                runCatching {
                    logger.event(id, "QUESTION", "QUESTION_ARCHIVE_SUBMISSION_FAILED", JSONObject().apply {
                        put("type", error.javaClass.name)
                        put("message", safeMessage)
                    })
                }
                message = "创建知乎问题归档失败：$safeMessage"
            }
        }
        return id
    }

    fun cancelTask(task: TaskRecord) {
        viewModelScope.launch {
            runCatching { logger.event(task.id, "DOWNLOAD", "CANCEL_REQUESTED") }
            try {
                if (task.questionArchiveId.isNotBlank() && !task.questionChild) {
                    zhihuQuestionArchiveCoordinator.cancel(task.id)
                } else {
                    scheduler.cancel(task.id)
                    store.update(task.id, TaskStatus.CANCELLED, "已取消", task.progress)
                }
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

    fun pauseTask(task: TaskRecord) {
        if (task.status !in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) return
        viewModelScope.launch {
            try {
                runCatching { logger.event(task.id, "DOWNLOAD", "PAUSE_REQUESTED") }
                store.update(task.id, TaskStatus.PAUSED, "已暂停", task.progress)
                scheduler.cancel(task.id)
                runCatching { logger.event(task.id, "DOWNLOAD", "PAUSE_ACCEPTED") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                message = "暂停失败：$detail"
            }
        }
    }

    fun resumeTask(task: TaskRecord) {
        if (task.status != TaskStatus.PAUSED) return
        viewModelScope.launch {
            try {
                store.update(task.id, TaskStatus.QUEUED, "等待下载", task.progress)
                scheduler.enqueue(task.id, ExistingWorkPolicy.REPLACE)
                runCatching { logger.event(task.id, "DOWNLOAD", "RESUME_ACCEPTED") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val detail = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                store.update(task.id, TaskStatus.PAUSED, "继续失败", task.progress, detail)
                message = "继续下载失败：$detail"
            }
        }
    }

    fun retryTask(task: TaskRecord) {
        if (task.questionArchiveId.isNotBlank() && !task.questionChild) {
            viewModelScope.launch {
                message = zhihuQuestionArchiveCoordinator.resume(task.id)
                refreshTasks()
            }
            return
        }
        val cookieHeader = CookieManager.getInstance()
            .getCookie(task.platform.homeUrl).orEmpty()
        viewModelScope.launch {
            message = redownloadCoordinator.retry(task, cookieHeader, customTreeUri).message
            refreshTasks()
        }
    }

    fun continueQuestionArchive(task: TaskRecord) {
        if (task.questionArchiveId.isBlank() || task.questionChild) return
        viewModelScope.launch {
            message = zhihuQuestionArchiveCoordinator.continueNextPage(task.id)
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
                if (task.questionArchiveId.isNotBlank() && !task.questionChild) {
                    val resumed = runCatching {
                        zhihuQuestionArchiveCoordinator.resume(task.id)
                    }.isSuccess
                    if (resumed) started += 1 else failed += 1
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

    internal suspend fun loadMarkdownOutput(taskId: String, output: TaskOutput): String? =
        withContext(Dispatchers.IO) {
            if (!inspector.outputExists(output.uri)) return@withContext null
            val uri = runCatching { Uri.parse(output.uri) }.getOrNull() ?: return@withContext null
            val input = runCatching {
                if (uri.scheme == "file") {
                    uri.path?.let { path -> java.io.File(path).inputStream() }
                } else {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                }
            }.getOrNull() ?: return@withContext null
            runCatching {
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    val text = StringBuilder()
                    val buffer = CharArray(8_192)
                    while (text.length < MAX_MARKDOWN_PREVIEW_CHARS) {
                        val count = reader.read(
                            buffer,
                            0,
                            minOf(buffer.size, MAX_MARKDOWN_PREVIEW_CHARS - text.length),
                        )
                        if (count < 0) break
                        text.append(buffer, 0, count)
                    }
                    text.toString()
                }
            }.onFailure { error ->
                logger.event(taskId, "DOCUMENT_READER", "MARKDOWN_READ_FAILED", JSONObject().apply {
                    put("name", output.displayName)
                    put("type", error.javaClass.name)
                    put("message", Redactor.sanitize(error.message.orEmpty()))
                })
            }.getOrNull()
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
            val result = deleteTaskIncludingQuestionChildren(task, deleteFiles)
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
                runCatching { deleteTaskIncludingQuestionChildren(task, deleteFiles) }
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

    private suspend fun deleteTaskIncludingQuestionChildren(
        task: TaskRecord,
        deleteFiles: Boolean,
    ): TaskDeleteResult {
        if (task.questionArchiveId.isBlank() || task.questionChild) {
            return deletionCoordinator.deleteTask(task.id, deleteFiles)
        }
        runCatching { zhihuQuestionArchiveCoordinator.cancel(task.id) }
        val children = zhihuQuestionRepository.listAnswers(task.questionArchiveId)
            .mapNotNull { it.taskId.takeIf(String::isNotBlank) }
        val childFailures = children.map { childTaskId ->
            deletionCoordinator.deleteTask(childTaskId, deleteFiles)
        }.count { !it.success }
        if (childFailures > 0) {
            return TaskDeleteResult(false, "$childFailures 个回答任务删除失败，请稍后重试")
        }
        val parentResult = deletionCoordinator.deleteTask(task.id, deleteFiles)
        if (parentResult.success) zhihuQuestionRepository.delete(task.questionArchiveId)
        return parentResult
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
            put("credential_state", _uiState.value.platformCredentialStates[platform]?.name.orEmpty())
        })
        refreshLogs()
    }

    fun onLoginEnvironmentClosed(platform: SourcePlatform) {
        CookieManager.getInstance().flush()
        refreshPlatformCredentialStates(forceXiaohongshuValidation = platform == SourcePlatform.XIAOHONGSHU)
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_ENVIRONMENT_CLOSED", JSONObject().apply {
            put("platform", platform.wireValue)
            put(
                "credential_detected",
                _uiState.value.platformCredentialStates[platform] == PlatformCredentialState.DETECTED,
            )
            put("credential_state", _uiState.value.platformCredentialStates[platform]?.name.orEmpty())
        })
        refreshLogs()
    }

    fun onLoginPageFinished(platform: SourcePlatform, url: String) {
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_PAGE_FINISHED", JSONObject().apply {
            put("platform", platform.wireValue)
            put("host", runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
            put(
                "credential_detected",
                _uiState.value.platformCredentialStates[platform] == PlatformCredentialState.DETECTED,
            )
            put("credential_state", _uiState.value.platformCredentialStates[platform]?.name.orEmpty())
        })
    }

    fun onLoginAssistResult(platform: SourcePlatform, url: String, result: String) {
        logger.event("app-login", "LOGIN_WEBVIEW", "LOGIN_ASSIST_RESULT", JSONObject().apply {
            put("platform", platform.wireValue)
            put("host", runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault(""))
            put("path", runCatching { Uri.parse(url).path.orEmpty() }.getOrDefault(""))
            put("result", Redactor.sanitize(result))
            put("desktop_mode", shouldUseDesktopLoginMode(platform))
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
        mediaPreviewCoordinator.pause("APP_BACKGROUNDED")
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
                fileStateRefresher.refresh(store.listAll())
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    private fun refreshPlatformCredentialStates(forceXiaohongshuValidation: Boolean = false) {
        val cookieManager = CookieManager.getInstance()
        if (forceXiaohongshuValidation) xiaohongshuCredentialCache.clear()
        val cookies = SourcePlatform.entries.associateWith { platform ->
            cookieManager.getCookie(platform.homeUrl).orEmpty()
        }
        val localStates = SourcePlatform.entries.associateWith { platform ->
            detectPlatformCredential(platform, cookies.getValue(platform))
        }.toMutableMap()
        if (localStates[SourcePlatform.XIAOHONGSHU] == PlatformCredentialState.DETECTED) {
            localStates[SourcePlatform.XIAOHONGSHU] = if (forceXiaohongshuValidation) {
                PlatformCredentialState.CHECKING
            } else {
                xiaohongshuCredentialCache.reusableState(
                    cookies.getValue(SourcePlatform.XIAOHONGSHU),
                    System.currentTimeMillis(),
                ) ?: PlatformCredentialState.CHECKING
            }
        } else {
            xiaohongshuCredentialCache.clear()
        }
        bilibiliCredentialJob?.cancel()
        val bilibiliCookie = cookies.getValue(SourcePlatform.BILIBILI)
        val checkBilibili = localStates[SourcePlatform.BILIBILI] == PlatformCredentialState.DETECTED
        if (checkBilibili) localStates[SourcePlatform.BILIBILI] = PlatformCredentialState.CHECKING
        _uiState.update { it.copy(platformCredentialStates = localStates.toMap()) }
        if (checkBilibili) {
            bilibiliCredentialJob = viewModelScope.launch {
                val validated = withContext(Dispatchers.IO) { bilibiliParser.credentialState(bilibiliCookie) }
                if (CookieManager.getInstance().getCookie(SourcePlatform.BILIBILI.homeUrl).orEmpty() == bilibiliCookie) {
                    _uiState.update { current -> current.copy(platformCredentialStates =
                        current.platformCredentialStates + (SourcePlatform.BILIBILI to validated)) }
                }
            }
        }
    }

    fun onXiaohongshuCredentialProbe(snapshot: WebPageSnapshot?) {
        if (_uiState.value.platformCredentialStates[SourcePlatform.XIAOHONGSHU] !=
            PlatformCredentialState.CHECKING
        ) {
            return
        }
        val state = snapshot?.initialData
            ?.let(::classifyXiaohongshuCredentialSnapshot)
            ?: PlatformCredentialState.UNVERIFIED
        val cookieHeader = CookieManager.getInstance()
            .getCookie(SourcePlatform.XIAOHONGSHU.homeUrl)
            .orEmpty()
        if (cookieHeader.isNotBlank()) {
            xiaohongshuCredentialCache.update(cookieHeader, state, System.currentTimeMillis())
        } else {
            xiaohongshuCredentialCache.clear()
        }
        _uiState.update { current ->
            current.copy(
                platformCredentialStates = current.platformCredentialStates.toMutableMap().apply {
                    put(SourcePlatform.XIAOHONGSHU, state)
                },
            )
        }
        logger.event("app-login", "LOGIN_STATUS", "CREDENTIAL_VALIDATED", JSONObject().apply {
            put("platform", SourcePlatform.XIAOHONGSHU.wireValue)
            put("state", state.name)
            put("source", "webview")
            put("final_path", runCatching {
                Uri.parse(snapshot?.finalUrl).path.orEmpty()
            }.getOrDefault(""))
        })
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

    fun checkForUpdates() {
        viewModelScope.launch { updateRepository.check(manual = true) }
    }

    fun downloadUpdate(source: UpdateSource) {
        if (updateDownloadJob?.isActive == true) return
        updateDownloadJob = viewModelScope.launch { updateRepository.download(source) }
    }

    fun cancelUpdateDownload() {
        updateRepository.cancelDownload()
        updateDownloadJob?.cancel()
    }

    fun requestUpdateInstall() {
        val request = updateRepository.installRequest()
        if (request == null) {
            message = "更新安装包不存在，请重新下载"
        } else {
            updateLaunchChannel.trySend(request)
            if (request is UpdateLaunchRequest.GrantInstallPermission) {
                message = "授权安装未知应用后，请再次点击安装"
            }
        }
    }

    fun openUpdateReleasePage() {
        updateLaunchChannel.trySend(updateRepository.releasePageRequest())
    }

    fun consumeMessage(): String {
        val current = message
        message = ""
        return current
    }

    override fun onCleared() {
        updateRepository.cancelDownload()
        mediaPreviewCoordinator.stopAndRelease("VIEW_MODEL_CLEARED")
        super.onCleared()
    }

    private fun preferredVariant(result: ParseResult): Int {
        return preferredVariant(result.variants)
    }

    private fun preferredVariant(variants: List<MediaVariant>): Int {
        if (!preferH264 || variants.isEmpty()) return 0
        val highest = variants.first()
        return variants.indexOfFirst {
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
