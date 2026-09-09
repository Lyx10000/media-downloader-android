package com.local.multiplatformdownloader.feature.home


import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.feature.download.DownloadScheduler
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
import com.local.multiplatformdownloader.feature.document.TaskContentCoordinator
import com.local.multiplatformdownloader.core.settings.SettingsRepository
import com.local.multiplatformdownloader.feature.tasks.ManagedFileItem
import com.local.multiplatformdownloader.feature.tasks.ManagedFileOperationResult
import com.local.multiplatformdownloader.feature.tasks.ManagedTransferMode
import com.local.multiplatformdownloader.feature.tasks.ShareableFile
import com.local.multiplatformdownloader.feature.tasks.TaskDeleteResult
import com.local.multiplatformdownloader.feature.tasks.TaskDeletionCoordinator
import com.local.multiplatformdownloader.feature.tasks.TaskFileStateRefresher
import com.local.multiplatformdownloader.feature.tasks.TaskCommandCoordinator
import com.local.multiplatformdownloader.feature.tasks.TaskInteractionCoordinator
import com.local.multiplatformdownloader.feature.tasks.TaskPreviewMedia
import com.local.multiplatformdownloader.feature.tasks.isTaskQueueVisible
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchive
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionArchiveCoordinator
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionDownloadScope
import com.local.multiplatformdownloader.feature.zhihuarchive.ZhihuQuestionRepository
import com.local.multiplatformdownloader.platform.bilibili.bilibiliPartResult
import com.local.multiplatformdownloader.platform.common.ParserGateway
import com.local.multiplatformdownloader.platform.common.PlatformCredentialState
import com.local.multiplatformdownloader.core.network.responseShape
import com.local.multiplatformdownloader.platform.xiaohongshu.XiaohongshuMediaParser
import com.local.multiplatformdownloader.platform.zhihu.ZhihuContentType
import com.local.multiplatformdownloader.platform.zhihu.ZhihuSourceResolver

import com.local.multiplatformdownloader.R

import android.app.Application
import android.content.Context
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
    val platformRiskCooldownMinutes: Map<SourcePlatform, Int> =
        com.local.multiplatformdownloader.core.settings.defaultPlatformRiskCooldownMinutes(),
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
    private val store: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val inspector: StorageInspector,
    private val deletionCoordinator: TaskDeletionCoordinator,
    private val settingsRepository: SettingsRepository,
    private val taskCommandCoordinator: TaskCommandCoordinator,
    private val scheduler: DownloadScheduler,
    private val fileStateRefresher: TaskFileStateRefresher,
    private val taskInteractionCoordinator: TaskInteractionCoordinator,
    private val taskContentCoordinator: TaskContentCoordinator,
    private val platformCredentialCoordinator: PlatformCredentialCoordinator,
    private val updateRepository: UpdateRepository,
    private val creatorRepository: CreatorLibraryRepository,
    private val zhihuQuestionArchiveCoordinator: ZhihuQuestionArchiveCoordinator,
    private val zhihuQuestionRepository: ZhihuQuestionRepository,
    trackDownloadProgressRegistry: TrackDownloadProgressRegistry,
    private val adaptiveDownloadController: AdaptiveDownloadController,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    internal val expandedTaskId = taskInteractionCoordinator.expandedTaskId
    internal val mediaPreviewState = taskInteractionCoordinator.mediaPreviewState
    internal val fullscreenTaskId = taskInteractionCoordinator.fullscreenTaskId
    internal val trackDownloadProgress = trackDownloadProgressRegistry.state
    internal val adaptiveDownloadState = adaptiveDownloadController.state
    private val _authorTaskPeakBytesPerSecond = MutableStateFlow<Map<String, Long>>(emptyMap())
    internal val authorTaskPeakBytesPerSecond: StateFlow<Map<String, Long>> =
        _authorTaskPeakBytesPerSecond.asStateFlow()
    internal val fileOperationTaskId = taskInteractionCoordinator.fileOperationTaskId
    private val diagnosticExportChannel = Channel<DiagnosticExportResult>(Channel.BUFFERED)
    internal val diagnosticExports = diagnosticExportChannel.receiveAsFlow()
    private val updateLaunchChannel = Channel<UpdateLaunchRequest>(Channel.BUFFERED)
    internal val updateLaunchRequests = updateLaunchChannel.receiveAsFlow()
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

    private val parseSession = ParseSessionController()
    private val refreshMutex = Mutex()
    private var taskCapabilities = emptyMap<String, TaskCapabilities>()
    private var tasksVisible = false

    init {
        viewModelScope.launch {
            platformCredentialCoordinator.states.collectLatest { states ->
                _uiState.update { it.copy(platformCredentialStates = states) }
            }
        }
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
                _uiState.update {
                    it.copy(platformRiskCooldownMinutes = settings.platformRiskCooldownMinutes)
                }
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
            adaptiveDownloadController.transferSpeedSnapshots.collectLatest(::updateAuthorTaskPeaks)
        }
        viewModelScope.launch {
            store.observeAll().collectLatest { records ->
                tasks = records.filterNot(TaskRecord::creatorChild)
                _uiState.update { state -> state.copy(allTasks = records) }
                updateAuthorTaskPeaks(adaptiveDownloadController.state.value.taskBytesPerSecond)
                taskInteractionCoordinator.reconcileTasks(records)
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
        _authorTaskPeakBytesPerSecond.update { previous ->
            accumulateAuthorTaskPeaks(previous, _uiState.value.allTasks, taskSpeeds)
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
        val supportsPageSnapshot = when (source.platform) {
            SourcePlatform.XIAOHONGSHU -> true
            SourcePlatform.ZHIHU -> runCatching {
                ZhihuSourceResolver.resolve(source.url).type != ZhihuContentType.VIDEO
            }.getOrDefault(false)
            SourcePlatform.DOUYIN, SourcePlatform.BILIBILI -> false
            SourcePlatform.X, SourcePlatform.INSTAGRAM -> true
        }
        val cookieHeader = CookieManager.getInstance()
            .getCookie(source.platform.homeUrl)
            .orEmpty()
        parseSession.begin(
            id = UUID.randomUUID().toString(),
            platform = source.platform,
            sourceUrl = source.url,
            supportsPageSnapshot = supportsPageSnapshot,
            storedCookieHeader = cookieHeader,
        )
        logger.event(parseSession.id, "INPUT", "LINK_ACCEPTED", JSONObject().apply {
            put("host", Uri.parse(source.url).host)
            put("platform", source.platform.wireValue)
        })
        when (val mode = initialParserCredentialMode(source.platform, cookieHeader.isNotBlank())) {
            ParserCredentialMode.ANONYMOUS -> {
                logger.event(parseSession.id, "COOKIE", "ANONYMOUS_PARSE_STARTED", JSONObject().apply {
                    put("present", false)
                    put("stored_cookie_present", cookieHeader.isNotBlank())
                })
                startParse("")
            }
            ParserCredentialMode.STORED_COOKIE -> {
                logger.event(
                    parseSession.id,
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
        if (parseSession.parsingStarted) return
        parseSession.storedCookieHeader = cookieHeader
        logger.event(parseSession.id, "COOKIE", "COOKIE_READY", JSONObject().apply {
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
        val credentialMode = parseSession.tryStartParsing(cookieHeader) ?: return
        parseState = ParseUiState.Parsing
        viewModelScope.launch {
            val result = parser.parse(inputText, cookieHeader, pageSnapshot)
            result.parserAttempts.forEach { attempt ->
                logger.event(
                    parseSession.id,
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
                !result.ok && parseSession.platform == SourcePlatform.XIAOHONGSHU &&
                result.canonicalUrl.isNotBlank() &&
                XiaohongshuMediaParser.noteIdFromUrl(result.canonicalUrl).isNotBlank()
            ) {
                parseSession.retainSourceUrl(result.canonicalUrl)
                logger.event(parseSession.id, "PARSE", "RESOLVED_TARGET_RETAINED", JSONObject().apply {
                    put("host", runCatching { Uri.parse(result.canonicalUrl).host }.getOrDefault(""))
                    put("path", runCatching { Uri.parse(result.canonicalUrl).path }.getOrDefault(""))
                    put("content_id", result.contentId)
                })
            }
            val fallbackMode = nextParserCredentialMode(
                platform = parseSession.platform,
                errorCode = result.errorCode,
                currentMode = credentialMode,
                hasStoredCookie = parseSession.storedCookieHeader.isNotBlank(),
                attemptedModes = parseSession.credentialAttempts,
            )
            if (fallbackMode != null) {
                logger.event(parseSession.id, "COOKIE", "CREDENTIAL_FALLBACK_STARTED", JSONObject().apply {
                    put("code", result.errorCode)
                    put("from", credentialMode.wireValue)
                    put("to", fallbackMode.wireValue)
                })
                parseSession.stopParsing()
                startParse(
                    if (fallbackMode == ParserCredentialMode.STORED_COOKIE) {
                        parseSession.storedCookieHeader
                    } else {
                        ""
                    },
                )
                refreshLogs()
                return@launch
            }
            if (shouldRefreshCookieEnvironment(
                    result.errorCode,
                    parseSession.environmentRefreshAttempted,
                    parseSession.platform,
                    parseSession.supportsPageSnapshot,
                )
            ) {
                logger.event(parseSession.id, "COOKIE", "COOKIE_REFRESH_REQUIRED", JSONObject().apply {
                    put("code", result.errorCode)
                })
                requestEnvironmentRefresh(result.errorCode.lowercase())
                refreshLogs()
                return@launch
            }
            if (result.ok) {
                adaptiveDownloadController.reportPlatformSuccess(result.platform)
                logger.event(parseSession.id, "PARSE", "DETAIL_PARSED", JSONObject().apply {
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
                logger.saveResponseShape(parseSession.id, result.responseShape)
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
                    parseSession.platform,
                    result.errorCode,
                    result.parserAttempts.map { it.statusCode },
                )
                logger.event(parseSession.id, "PARSE", "PARSE_FAILED", JSONObject().apply {
                    put("code", result.errorCode)
                    put("message", result.message)
                })
                parseState = ParseUiState.Error(result.errorCode, result.message)
            }
            refreshLogs()
        }
    }

    fun retryParse() {
        parseSession.stopParsing()
        beginParse()
    }

    fun resetParse() {
        parseState = ParseUiState.Idle
        parseSession.reset()
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
        val id = parseSession.id.ifBlank { UUID.randomUUID().toString() }
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
        val id = parseSession.id.ifBlank { UUID.randomUUID().toString() }
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
            taskCommandCoordinator.cancel(task)?.let { message = it }
        }
    }

    fun pauseTask(task: TaskRecord) {
        if (task.status !in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) return
        viewModelScope.launch {
            taskCommandCoordinator.pause(task)?.let { message = it }
        }
    }

    fun resumeTask(task: TaskRecord) {
        if (task.status != TaskStatus.PAUSED) return
        viewModelScope.launch {
            taskCommandCoordinator.resume(task)?.let { message = it }
        }
    }

    fun retryTask(task: TaskRecord) {
        viewModelScope.launch {
            message = taskCommandCoordinator.retry(task, customTreeUri)
            refreshTasks()
        }
    }

    fun continueQuestionArchive(task: TaskRecord) {
        if (task.questionArchiveId.isBlank() || task.questionChild) return
        viewModelScope.launch {
            taskCommandCoordinator.continueQuestionArchive(task)?.let { message = it }
            refreshTasks()
        }
    }

    fun retryTasks(tasks: List<TaskRecord>) {
        if (tasks.isEmpty()) return
        viewModelScope.launch {
            taskCommandCoordinator.retryAll(tasks, customTreeUri)?.let { message = it }
            refreshTasks()
        }
    }
    internal fun shareTaskFiles(context: Context, taskId: String, files: List<ShareableFile>) {
        message = "正在准备分享…"
        viewModelScope.launch {
            val resultMessage = taskInteractionCoordinator.share(context, taskId, files)
            if (resultMessage.isNotBlank()) message = resultMessage
        }
    }

    internal fun toggleTaskPreview(taskId: String) {
        taskInteractionCoordinator.togglePreview(taskId)
    }

    internal suspend fun resolveTaskPreview(
        taskId: String,
        outputs: List<TaskOutput>,
    ): List<TaskPreviewMedia> = taskInteractionCoordinator.resolvePreview(taskId, outputs)

    internal fun toggleMediaPreview(taskId: String, media: TaskPreviewMedia) {
        taskInteractionCoordinator.toggleMedia(taskId, media)
    }

    internal fun seekMediaPreview(taskId: String, positionMs: Long) {
        taskInteractionCoordinator.seek(taskId, positionMs)
    }

    internal fun toggleMediaMute(taskId: String) {
        taskInteractionCoordinator.toggleMute(taskId)
    }

    internal fun enterFullscreen(taskId: String, media: TaskPreviewMedia) {
        taskInteractionCoordinator.enterFullscreen(taskId, media)
    }

    internal fun exitFullscreen() {
        taskInteractionCoordinator.exitFullscreen()
    }

    internal fun stopMediaPreviewIfTask(taskId: String, reason: String = "PREVIEW_DISPOSED") {
        taskInteractionCoordinator.stopPreviewIfTask(taskId, reason)
    }

    internal fun openTaskFolder(context: Context, task: TaskRecord) {
        viewModelScope.launch {
            val resultMessage = taskInteractionCoordinator.openTaskFolder(context, task)
            if (resultMessage.isNotBlank()) message = resultMessage
        }
    }

    internal fun onFileManagerOpened(taskId: String) {
        taskInteractionCoordinator.onFileManagerOpened(taskId)
    }

    internal suspend fun describeManagedFiles(outputs: List<TaskOutput>): List<ManagedFileItem> =
        taskInteractionCoordinator.describeFiles(outputs)

    internal suspend fun loadDocumentReader(task: TaskRecord): DocumentReaderData? =
        taskContentCoordinator.loadDocumentReader(task)

    internal suspend fun loadMarkdownOutput(taskId: String, output: TaskOutput): String? =
        taskContentCoordinator.loadMarkdownOutput(taskId, output)

    internal fun openDocumentMedia(context: Context, taskId: String, output: TaskOutput) {
        taskContentCoordinator.openDocumentMedia(context, taskId, output)?.let { message = it }
    }

    internal fun openManagedFile(context: Context, taskId: String, item: ManagedFileItem) {
        taskContentCoordinator.openManagedFile(context, taskId, item)?.let { message = it }
    }
    internal fun renameManagedFile(taskId: String, outputUri: String, requestedBase: String) {
        launchFileOperation(taskId) {
            taskInteractionCoordinator.rename(taskId, outputUri, requestedBase)
        }
    }

    internal fun deleteManagedFiles(taskId: String, outputUris: Set<String>) {
        launchFileOperation(taskId) {
            taskInteractionCoordinator.delete(taskId, outputUris)
        }
    }

    internal fun transferManagedFiles(
        taskId: String,
        outputUris: Set<String>,
        destinationTree: Uri,
        mode: ManagedTransferMode,
    ) {
        launchFileOperation(taskId) {
            taskInteractionCoordinator.transfer(taskId, outputUris, destinationTree, mode)
        }
    }

    internal fun showMessage(value: String) {
        message = value
    }

    private fun launchFileOperation(
        taskId: String,
        operation: suspend () -> ManagedFileOperationResult,
    ) {
        if (!taskInteractionCoordinator.beginFileOperation(taskId)) {
            message = "另一个文件操作正在进行"
            return
        }
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
                taskInteractionCoordinator.finishFileOperation()
            }
        }
    }

    fun deleteTask(task: TaskRecord, deleteFiles: Boolean) {
        taskInteractionCoordinator.closeTask(task.id, "TASK_DELETE_REQUESTED")
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
            taskInteractionCoordinator.closeTask(task.id, "BATCH_DELETE_REQUESTED")
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
            taskContentCoordinator.recordImageLoadFailed(
                taskId,
                assetId,
                outputName,
                sourceScheme,
                error,
            )
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
        platformCredentialCoordinator.onEnvironmentOpened(viewModelScope, platform)
        refreshLogs()
    }

    fun onLoginEnvironmentClosed(platform: SourcePlatform) {
        platformCredentialCoordinator.onEnvironmentClosed(viewModelScope, platform)
        refreshLogs()
    }

    fun onLoginPageFinished(platform: SourcePlatform, url: String) {
        platformCredentialCoordinator.onPageFinished(platform, url)
    }

    fun onLoginAssistResult(platform: SourcePlatform, url: String, result: String) {
        platformCredentialCoordinator.onAssistResult(platform, url, result)
    }

    fun onLoginPageError(
        platform: SourcePlatform,
        url: String,
        errorCode: Int,
        description: String,
    ) {
        platformCredentialCoordinator.onPageError(platform, url, errorCode, description)
        refreshLogs()
    }

    fun onAppBackground() {
        taskInteractionCoordinator.pause("APP_BACKGROUNDED")
    }

    fun onTasksVisible() {
        tasksVisible = true
        refreshTasks()
    }

    fun onTasksHidden() {
        tasksVisible = false
        taskInteractionCoordinator.stopAndRelease()
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
        platformCredentialCoordinator.refresh(viewModelScope, forceXiaohongshuValidation)
    }

    fun onXiaohongshuCredentialProbe(snapshot: WebPageSnapshot?) {
        platformCredentialCoordinator.onXiaohongshuProbe(snapshot)
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

    fun updatePlatformRiskCooldownMinutes(platform: SourcePlatform, minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setPlatformRiskCooldownMinutes(platform, minutes)
        }
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
        platformCredentialCoordinator.cancel()
        taskInteractionCoordinator.stopAndRelease("VIEW_MODEL_CLEARED")
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
        parseSession.markEnvironmentRefreshAttempted()
        logger.event(parseSession.id, "COOKIE", "COOKIE_WARMUP_STARTED", JSONObject().put("reason", reason))
        parseState = ParseUiState.LoadingWeb(
            platform = parseSession.platform,
            url = if (parseSession.supportsPageSnapshot) {
                parseSession.sourceUrl
            } else {
                parseSession.platform.homeUrl
            },
            capturePage = parseSession.supportsPageSnapshot,
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

internal fun accumulateAuthorTaskPeaks(
    previous: Map<String, Long>,
    tasks: List<TaskRecord>,
    taskSpeeds: Map<String, Long>,
): Map<String, Long> = tasks.asSequence()
    .filter { taskCreatorKey(it).isNotBlank() && isTaskQueueVisible(it) }
    .groupBy(::taskCreatorKey)
    .mapValues { (authorKey, activeTasks) ->
        maxOf(previous[authorKey] ?: 0L, activeTasks.sumOf { taskSpeeds[it.id] ?: 0L })
    }
