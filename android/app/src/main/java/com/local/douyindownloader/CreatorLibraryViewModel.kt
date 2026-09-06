package com.local.douyindownloader

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

enum class HomeInputMode { WORK, CREATOR }

data class CreatorWebResolveRequest(
    val platform: SourcePlatform,
    val query: String,
    val url: String,
)

data class CreatorPageWebRefreshRequest(
    val requestId: Long,
    val creatorKey: String,
    val url: String,
    val page: Int,
    val cursor: String,
)

data class CreatorLibraryUiState(
    val creators: List<CreatorProfile> = emptyList(),
    val platformFilter: SourcePlatform? = null,
    val query: String = "",
    val queryPlatform: SourcePlatform = SourcePlatform.DOUYIN,
    val candidate: CreatorProfile? = null,
    val selectedCreator: CreatorProfile? = null,
    val pageWorks: List<CreatorWork> = emptyList(),
    val allWorks: List<CreatorWork> = emptyList(),
    val pageNumber: Int = 1,
    val hasPrevious: Boolean = false,
    val hasMore: Boolean = false,
    val selectedWorkKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isStartingBatch: Boolean = false,
    val error: String = "",
    val showRiskWarning: Boolean = false,
    val batchSettings: BatchDownloadSettings = BatchDownloadSettings(),
    val estimate: BatchSizeEstimate = BatchSizeEstimate(0L, 0L, 0, 0),
    val availableBytes: Long? = null,
    val totalBytes: Long? = null,
    val taskSummaries: Map<String, CreatorTaskSummary> = emptyMap(),
    val batchSummaries: Map<String, CreatorBatchSummary> = emptyMap(),
    val batchPreparations: Map<String, CreatorBatchPreparation> = emptyMap(),
    val homeInputMode: HomeInputMode = HomeInputMode.WORK,
    val webResolveRequest: CreatorWebResolveRequest? = null,
    val pageWebRefreshRequest: CreatorPageWebRefreshRequest? = null,
    val isDeletingCreators: Boolean = false,
)

data class CreatorTaskSummary(
    val total: Int,
    val running: Int,
    val complete: Int,
    val failed: Int,
)

internal data class CreatorDetailListPosition(
    val index: Int = 0,
    val offset: Int = 0,
)

@HiltViewModel
class CreatorLibraryViewModel @Inject internal constructor(
    private val creatorSources: CreatorSourceRouter,
    private val creatorRepository: CreatorLibraryRepository,
    private val creatorBatchCoordinator: CreatorBatchCoordinator,
    private val taskRepository: DownloadTaskRepository,
    private val settingsRepository: SettingsRepository,
    private val inspector: StorageInspector,
    private val deletionCoordinator: TaskDeletionCoordinator,
    private val taskFolderPruner: TaskFolderPruner,
    private val logger: DiagnosticLogger,
) : ViewModel() {
    private val _state = MutableStateFlow(CreatorLibraryUiState())
    val state: StateFlow<CreatorLibraryUiState> = _state.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var worksJob: Job? = null
    private val cursorByPage = mutableMapOf(1 to "")
    private var nextCursor = ""
    private var customTreeUri: String? = null
    private var observedTasks: List<TaskRecord> = emptyList()
    private val detailListPositions = mutableMapOf<String, CreatorDetailListPosition>()
    private val detailTabs = mutableMapOf<String, Int>()
    private val lastManualRefreshAt = mutableMapOf<String, Long>()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { settings ->
                customTreeUri = settings.customTreeUri
                _state.update { it.copy(batchSettings = settings.batchDownloadSettings) }
                refreshEstimate()
            }
        }
        viewModelScope.launch {
            creatorRepository.observeCreators().collectLatest { creators ->
                _state.update { current ->
                    current.copy(
                        creators = creators.filter { it.followed || it.archived },
                        selectedCreator = current.selectedCreator?.let { selected ->
                            creators.firstOrNull { it.key == selected.key } ?: selected
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            taskRepository.observeAll().collectLatest { tasks ->
                observedTasks = tasks
                _state.update { current ->
                    current.copy(
                        taskSummaries = tasks.filter { it.authorKey.isNotBlank() }
                            .groupBy(TaskRecord::authorKey)
                            .mapValues { (_, authorTasks) ->
                                CreatorTaskSummary(
                                    total = authorTasks.size,
                                    running = authorTasks.count {
                                        it.status in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)
                                    },
                                    complete = authorTasks.count { it.status == TaskStatus.COMPLETE },
                                    failed = authorTasks.count {
                                        it.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED)
                                    },
                                )
                            },
                    )
                }
            }
        }
        viewModelScope.launch {
            creatorRepository.observeBatchSummaries().collectLatest { summaries ->
                _state.update { it.copy(batchSummaries = summaries) }
            }
        }
        viewModelScope.launch {
            creatorRepository.observeBatchPreparations().collectLatest { preparations ->
                _state.update { it.copy(batchPreparations = preparations) }
            }
        }
    }

    fun setHomeInputMode(mode: HomeInputMode) {
        _state.update {
            it.copy(homeInputMode = mode, candidate = null, error = "", webResolveRequest = null)
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value, candidate = null) }
    }

    fun setQueryPlatform(platform: SourcePlatform) {
        if (platform !in CREATOR_BATCH_PLATFORMS) return
        _state.update { it.copy(queryPlatform = platform, candidate = null) }
    }

    fun setPlatformFilter(platform: SourcePlatform?) {
        _state.update { it.copy(platformFilter = platform) }
    }

    internal fun detailListPosition(creatorKey: String, tab: Int): CreatorDetailListPosition =
        detailListPositions["$creatorKey:$tab"] ?: CreatorDetailListPosition()

    internal fun detailTab(creatorKey: String): Int = detailTabs[creatorKey] ?: 0

    internal fun saveDetailTab(creatorKey: String, tab: Int) {
        detailTabs[creatorKey] = tab.coerceIn(0, 1)
    }

    internal fun saveDetailListPosition(
        creatorKey: String,
        tab: Int,
        index: Int,
        offset: Int,
    ) {
        detailListPositions["$creatorKey:$tab"] = CreatorDetailListPosition(
            index = index.coerceAtLeast(0),
            offset = offset.coerceAtLeast(0),
        )
    }

    fun findCreator() {
        val current = _state.value
        val query = current.query.trim()
        if (query.isBlank()) {
            notify("请输入作者主页链接")
            return
        }
        val supportedSource = extractSupportedSource(query) ?: run {
            notify("请粘贴抖音或知乎作者主页链接")
            return
        }
        val url = supportedSource.url
        val platform = supportedSource.platform
        if (platform !in CREATOR_BATCH_PLATFORMS) {
            _state.update {
                it.copy(
                    isLoading = false,
                    candidate = null,
                    error = "小红书作者批量下载已停止支持，请在作品模式粘贴单个作品链接",
                )
            }
            return
        }
        _state.update {
            it.copy(isLoading = true, candidate = null, error = "", queryPlatform = platform)
        }
        viewModelScope.launch {
            val cookie = CookieManager.getInstance().getCookie(platform.homeUrl).orEmpty()
            resolveCandidate(platform, url, cookie)
        }
    }

    fun completeWebResolve(
        cookieHeader: String,
        source: CookieReadySource,
        snapshot: WebPageSnapshot?,
    ) {
        val request = _state.value.webResolveRequest ?: return
        _state.update { it.copy(webResolveRequest = null, isLoading = true) }
        if (snapshot == null) {
            val reason = if (source == CookieReadySource.TIMEOUT) "小红书作者搜索加载超时" else "小红书作者搜索页面没有返回数据"
            _state.update { it.copy(isLoading = false, error = reason) }
            return
        }
        viewModelScope.launch {
            resolveCandidate(request.platform, request.query, cookieHeader, snapshot)
        }
    }

    fun cancelWebResolve() {
        _state.update {
            it.copy(webResolveRequest = null, isLoading = false, error = "已取消查找作者")
        }
    }

    private suspend fun resolveCandidate(
        platform: SourcePlatform,
        query: String,
        cookieHeader: String,
        snapshot: WebPageSnapshot? = null,
    ) {
        try {
            val profile = withContext(Dispatchers.IO) {
                creatorSources.resolve(platform, query, cookieHeader, snapshot)
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    webResolveRequest = null,
                    candidate = profile.copy(followed = true, archived = false),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val safe = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            logger.event("creator-search", "CREATOR", "PROFILE_RESOLVE_FAILED", JSONObject().apply {
                put("platform", platform.wireValue)
                put("code", (error as? CreatorSourceException)?.code.orEmpty())
                put("message", safe)
            })
            _state.update { it.copy(isLoading = false, webResolveRequest = null, error = safe) }
        }
    }

    fun confirmCreator(onConfirmed: () -> Unit = {}) {
        val candidate = _state.value.candidate ?: return
        viewModelScope.launch {
            creatorRepository.upsert(candidate.copy(followed = true, archived = false))
            _state.update { it.copy(candidate = null, query = "") }
            openCreator(candidate.key)
            onConfirmed()
        }
    }

    fun openCreator(key: String) {
        worksJob?.cancel()
        cursorByPage.clear()
        cursorByPage[1] = ""
        nextCursor = ""
        viewModelScope.launch {
            val profile = creatorRepository.getCreator(key) ?: return@launch
            if (profile.platform !in CREATOR_BATCH_PLATFORMS) {
                notify("小红书作者批量下载已停止支持")
                return@launch
            }
            creatorBatchCoordinator.recoverInterruptedPreparation(key)
            _state.update {
                it.copy(
                    selectedCreator = profile,
                    pageNumber = 1,
                    pageWorks = emptyList(),
                    allWorks = emptyList(),
                    selectedWorkKeys = emptySet(),
                    hasPrevious = false,
                    hasMore = false,
                    error = "",
                    pageWebRefreshRequest = null,
                )
            }
            worksJob = viewModelScope.launch {
                creatorRepository.observeWorks(key).collectLatest { works ->
                    val page = _state.value.pageNumber
                    val visiblePage = visibleCreatorPage(
                        works = works,
                        pageNumber = page,
                        limit = if (profile.platform == SourcePlatform.ZHIHU) {
                            CREATOR_PAGE_SIZE
                        } else Int.MAX_VALUE,
                    )
                    _state.update {
                        it.copy(
                            pageWorks = visiblePage,
                            allWorks = works,
                        )
                    }
                    refreshEstimate()
                }
            }
            val cached = creatorRepository.getPage(key, 1)
            val info = creatorRepository.getPageInfo(key, 1)
            if (cached.isNotEmpty()) {
                nextCursor = info?.nextCursor.orEmpty()
                _state.update { it.copy(pageWorks = cached, hasMore = info?.hasMore == true) }
            }
            val stale = System.currentTimeMillis() - profile.refreshedAt >= CACHE_MAX_AGE_MS
            val legacyOversizedPage = profile.platform == SourcePlatform.ZHIHU &&
                cached.size > CREATOR_PAGE_SIZE
            if (profile.followed && (cached.isEmpty() || stale || legacyOversizedPage)) {
                loadPage(1, "", force = true)
            }
        }
    }

    fun closeCreator() {
        val closingKey = _state.value.selectedCreator?.key
        pauseBatchPreparation(showMessage = false)
        worksJob?.cancel()
        worksJob = null
        closingKey?.let { creatorKey ->
            detailTabs.remove(creatorKey)
            detailListPositions.keys.removeAll { it.startsWith("$creatorKey:") }
        }
        _state.update {
            it.copy(
                selectedCreator = null,
                pageWorks = emptyList(),
                allWorks = emptyList(),
                selectedWorkKeys = emptySet(),
                error = "",
                pageWebRefreshRequest = null,
                isLoading = false,
            )
        }
    }

    fun refreshCreator() {
        val profile = _state.value.selectedCreator ?: return
        val now = System.currentTimeMillis()
        val previous = lastManualRefreshAt[profile.key] ?: 0L
        if (profile.platform == SourcePlatform.DOUYIN && now - previous < MANUAL_REFRESH_COOLDOWN_MS) {
            messageChannel.trySend("刷新过于频繁，请稍候再试")
            return
        }
        lastManualRefreshAt[profile.key] = now
        val page = _state.value.pageNumber
        loadPage(page, cursorByPage[page].orEmpty(), force = true)
    }

    fun nextPage() {
        val current = _state.value
        if (!current.hasMore || nextCursor.isBlank()) return
        val page = current.pageNumber + 1
        cursorByPage[page] = nextCursor
        loadPage(page, nextCursor, force = false)
    }

    fun previousPage() {
        val page = (_state.value.pageNumber - 1).coerceAtLeast(1)
        viewModelScope.launch {
            val profile = _state.value.selectedCreator ?: return@launch
            val cached = creatorRepository.getPage(profile.key, page)
            val info = creatorRepository.getPageInfo(profile.key, page)
            nextCursor = info?.nextCursor.orEmpty()
            _state.update {
                it.copy(
                    pageNumber = page,
                    pageWorks = cached,
                    hasPrevious = page > 1,
                    hasMore = info?.hasMore == true,
                    error = "",
                )
            }
        }
    }

    private fun loadPage(
        page: Int,
        cursor: String,
        force: Boolean,
        pageSnapshot: WebPageSnapshot? = null,
        cookieOverride: String? = null,
        allowWebFallback: Boolean = true,
    ) {
        val profile = _state.value.selectedCreator ?: return
        viewModelScope.launch {
            if (!force) {
                val cached = creatorRepository.getPage(profile.key, page)
                val compatibleCache = profile.platform != SourcePlatform.ZHIHU ||
                    cached.size <= CREATOR_PAGE_SIZE
                if (cached.isNotEmpty() && compatibleCache) {
                    val info = creatorRepository.getPageInfo(profile.key, page)
                    nextCursor = info?.nextCursor.orEmpty()
                    _state.update {
                        it.copy(
                            pageNumber = page,
                            pageWorks = cached,
                            hasPrevious = page > 1,
                            hasMore = info?.hasMore == true,
                            error = "",
                        )
                    }
                    return@launch
                }
            }
            _state.update { it.copy(isLoading = true, error = "") }
            val logId = "creator-${profile.key.hashCode().toUInt()}"
            logger.event(logId, "CREATOR", "PAGE_REFRESH_STARTED", JSONObject().apply {
                put("platform", profile.platform.wireValue)
                put("page", page)
                put("has_cursor", cursor.isNotBlank())
            })
            val cookie = cookieOverride
                ?: CookieManager.getInstance().getCookie(profile.platform.homeUrl).orEmpty()
            try {
                val result = withContext(Dispatchers.IO) {
                    creatorSources.fetchPage(profile, cursor, page, cookie, pageSnapshot)
                }
                creatorRepository.savePage(result)
                val mergedPageWorks = creatorRepository.getPage(profile.key, page)
                logger.event(logId, "CREATOR", "PAGE_REFRESH_COMPLETE", JSONObject().apply {
                    put("page", page)
                    put("works", result.works.size)
                    put("has_more", result.hasMore)
                })
                nextCursor = result.nextCursor
                _state.update {
                    it.copy(
                        selectedCreator = result.profile,
                        pageWorks = mergedPageWorks,
                        pageNumber = page,
                        hasPrevious = page > 1,
                        hasMore = result.hasMore,
                        isLoading = false,
                        error = "",
                        pageWebRefreshRequest = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val safe = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
                val code = (error as? CreatorSourceException)?.code.orEmpty()
                if (
                    allowWebFallback &&
                    profile.platform == SourcePlatform.XIAOHONGSHU &&
                    code in XHS_CREATOR_WEB_FALLBACK_ERRORS
                ) {
                    creatorRepository.restorePageAfterAmbiguousRefresh(profile.key, page)
                    val retained = creatorRepository.getPage(profile.key, page)
                    logger.event(logId, "CREATOR", "PAGE_WEB_REFRESH_REQUIRED", JSONObject().apply {
                        put("page", page)
                        put("code", code)
                        put("retained_works", retained.size)
                    })
                    _state.update {
                        it.copy(
                            pageWorks = retained,
                            pageWebRefreshRequest = CreatorPageWebRefreshRequest(
                                requestId = System.currentTimeMillis(),
                                creatorKey = profile.key,
                                url = profile.profileUrl,
                                page = page,
                                cursor = cursor,
                            ),
                            isLoading = true,
                            error = "",
                        )
                    }
                    return@launch
                }
                if (
                    profile.platform == SourcePlatform.XIAOHONGSHU &&
                    code in XHS_CREATOR_WEB_FALLBACK_ERRORS
                ) {
                    creatorRepository.restorePageAfterAmbiguousRefresh(profile.key, page)
                }
                creatorRepository.recordRefreshFailure(
                    profile,
                    safe,
                    if (code == "AUTHOR_NOT_FOUND") {
                        CreatorAccountStatus.INACCESSIBLE
                    } else CreatorAccountStatus.REFRESH_FAILED,
                )
                logger.event(logId, "CREATOR", "PAGE_REFRESH_FAILED", JSONObject().apply {
                    put("page", page)
                    put("code", code)
                    put("message", safe)
                })
                val retained = creatorRepository.getPage(profile.key, page)
                _state.update {
                    it.copy(
                        pageWorks = retained,
                        pageWebRefreshRequest = null,
                        isLoading = false,
                        error = safe,
                    )
                }
            }
        }
    }

    fun completePageWebRefresh(
        cookieHeader: String,
        source: CookieReadySource,
        snapshot: WebPageSnapshot?,
    ) {
        val request = _state.value.pageWebRefreshRequest ?: return
        if (_state.value.selectedCreator?.key != request.creatorKey) return
        logger.event("creator-${request.creatorKey.hashCode().toUInt()}", "CREATOR", "PAGE_WEB_SNAPSHOT_READY", JSONObject().apply {
            put("page", request.page)
            put("source", source.name.lowercase())
            put("snapshot", snapshot != null)
            put("initial_bytes", snapshot?.initialData?.length ?: 0)
            put("content_bytes", snapshot?.contentHtml?.length ?: 0)
            put("visible_bytes", snapshot?.visibleText?.length ?: 0)
        })
        _state.update { it.copy(pageWebRefreshRequest = null) }
        if (snapshot == null) {
            viewModelScope.launch {
                val profile = _state.value.selectedCreator ?: return@launch
                creatorRepository.restorePageAfterAmbiguousRefresh(profile.key, request.page)
                val retained = creatorRepository.getPage(profile.key, request.page)
                _state.update {
                    it.copy(
                        pageWorks = retained,
                        isLoading = false,
                        error = "小红书网页刷新未取得作品数据，已保留上次缓存",
                    )
                }
            }
            return
        }
        loadPage(
            page = request.page,
            cursor = request.cursor,
            force = true,
            pageSnapshot = snapshot,
            cookieOverride = cookieHeader,
            allowWebFallback = false,
        )
    }

    fun cancelPageWebRefresh(showMessage: Boolean = true) {
        val request = _state.value.pageWebRefreshRequest ?: return
        viewModelScope.launch {
            creatorRepository.restorePageAfterAmbiguousRefresh(request.creatorKey, request.page)
            val retained = creatorRepository.getPage(request.creatorKey, request.page)
            _state.update {
                it.copy(
                    pageWorks = retained,
                    pageWebRefreshRequest = null,
                    isLoading = false,
                    error = if (showMessage) "已取消刷新，原有作品缓存未受影响" else "",
                )
            }
        }
    }

    fun toggleWork(work: CreatorWork) {
        val selected = _state.value.selectedWorkKeys
        val selecting = work.key !in selected
        if (selecting && work.localStatus == CreatorWorkLocalStatus.AVAILABLE) {
            notify("该作品本地已有文件，已选中后将重新下载并替换登记内容")
        }
        _state.update {
            it.copy(selectedWorkKeys = if (selecting) selected + work.key else selected - work.key)
        }
        refreshEstimate()
    }

    fun selectCurrentPage() {
        val current = _state.value
        val selected = selectCurrentCreatorPage(current.selectedWorkKeys, current.pageWorks)
        _state.update {
            it.copy(
                selectedWorkKeys = selected,
                showRiskWarning = current.pageNumber > 1 || selected.size >= RISK_SELECTION_THRESHOLD,
            )
        }
        refreshEstimate()
    }

    fun clearSelection() {
        _state.update {
            it.copy(
                selectedWorkKeys = emptySet(),
                estimate = BatchSizeEstimate(0L, 0L, 0, 0),
            )
        }
    }

    fun dismissRiskWarning() {
        _state.update { it.copy(showRiskWarning = false) }
    }

    fun updateBatchSettings(settings: BatchDownloadSettings) {
        _state.update { it.copy(batchSettings = settings) }
        refreshEstimate()
    }

    fun startBatch() {
        val current = _state.value
        val profile = current.selectedCreator ?: return
        if (profile.platform !in CREATOR_BATCH_PLATFORMS) {
            notify("小红书作者批量下载已停止支持")
            return
        }
        if (current.selectedWorkKeys.isEmpty() || current.isStartingBatch) return
        viewModelScope.launch {
            _state.update { it.copy(isStartingBatch = true) }
            try {
                val result = creatorBatchCoordinator.start(
                    profile,
                    current.selectedWorkKeys,
                    current.batchSettings,
                )
                notify(result.message)
                clearSelection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify("创建批量任务失败：${Redactor.sanitize(error.message ?: error.javaClass.simpleName)}")
            } finally {
                _state.update { it.copy(isStartingBatch = false) }
            }
        }
    }

    fun resumePausedBatch() {
        val creatorKey = _state.value.selectedCreator?.key ?: return
        viewModelScope.launch {
            notify(creatorBatchCoordinator.resume(creatorKey))
        }
    }

    fun completeBatchWebPreparation(
        request: CreatorBatchWebRequest,
        cookieHeader: String,
        source: CookieReadySource,
        snapshot: WebPageSnapshot?,
    ) {
        viewModelScope.launch {
            val outcome = creatorBatchCoordinator.completeWebPreparation(
                request = request,
                cookieHeader = cookieHeader,
                readySource = source,
                snapshot = snapshot,
            )
            if (outcome.message.isNotBlank()) notify(outcome.message)
        }
    }

    fun pauseBatchPreparation(showMessage: Boolean = true) {
        val creatorKey = _state.value.selectedCreator?.key ?: return
        viewModelScope.launch {
            if (creatorBatchCoordinator.pauseForegroundPreparation(creatorKey) && showMessage) {
                notify("批量准备已暂停，返回作者页面后可继续")
            }
        }
    }

    fun stopFollowing(profile: CreatorProfile) {
        viewModelScope.launch {
            val hasDownloads = taskRepository.listForAuthor(profile.key).isNotEmpty() ||
                (_state.value.batchSummaries[profile.key]?.selected ?: 0) > 0
            creatorRepository.setFollowed(profile, followed = false, hasDownloads = hasDownloads)
            if (_state.value.selectedCreator?.key == profile.key) closeCreator()
        }
    }

    fun followCreator(profile: CreatorProfile) {
        viewModelScope.launch {
            creatorRepository.setFollowed(profile, followed = true, hasDownloads = true)
            val refreshed = creatorRepository.getCreator(profile.key)
            if (refreshed != null) {
                _state.update { it.copy(selectedCreator = refreshed) }
                loadPage(1, "", force = true)
            }
        }
    }

    fun deleteArchived(profile: CreatorProfile, deleteFiles: Boolean) {
        viewModelScope.launch {
            try {
                creatorBatchCoordinator.cancelForCreator(profile.key)
                val authorTasks = taskRepository.listForAuthor(profile.key)
                val specs = authorTasks.mapNotNull { taskRepository.getSpec(it.id) }
                var failures = 0
                authorTasks.forEach { task ->
                    if (!deletionCoordinator.deleteTask(task.id, deleteFiles).success) failures += 1
                }
                if (failures == 0) {
                    if (deleteFiles) specs.forEach(taskFolderPruner::pruneCreatorParents)
                    creatorRepository.deleteCreatorRecords(profile)
                    closeCreator()
                    notify(if (deleteFiles) "作者记录和下载目录已删除" else "作者记录已删除，本地文件已保留")
                } else {
                    notify("$failures 个任务未能删除，作者归档已保留")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify("删除作者归档失败：${Redactor.sanitize(error.message ?: error.javaClass.simpleName)}")
            }
        }
    }

    fun deleteRequiresAllFilesAccess(profile: CreatorProfile): Boolean =
        observedTasks.any { task ->
            task.authorKey == profile.key && task.storageMode != StorageMode.SAF
        } && !StorageInspector.hasAllFilesAccess()

    fun bulkDeleteRequiresAllFilesAccess(creatorKeys: Set<String>): Boolean {
        val activeKeys = _state.value.creators.asSequence()
            .filter { it.key in creatorKeys && !it.archived }
            .mapTo(hashSetOf(), CreatorProfile::key)
        return activeKeys.isNotEmpty() &&
            observedTasks.any { it.authorKey in activeKeys && it.storageMode != StorageMode.SAF } &&
            !StorageInspector.hasAllFilesAccess()
    }

    fun deleteCreators(creatorKeys: Set<String>) {
        if (creatorKeys.isEmpty() || _state.value.isDeletingCreators) return
        val profiles = _state.value.creators.filter { it.key in creatorKeys }
        if (profiles.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isDeletingCreators = true) }
            try {
                val result = withContext(Dispatchers.IO) {
                    deleteCreatorSelection(profiles)
                }
                notify(result.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                notify("批量删除作者失败：${Redactor.sanitize(error.message ?: error.javaClass.simpleName)}")
            } finally {
                _state.update { it.copy(isDeletingCreators = false) }
            }
        }
    }

    private suspend fun deleteCreatorSelection(profiles: List<CreatorProfile>): CreatorDeleteResult {
        var deletedAuthors = 0
        var deletedArchives = 0
        var deletedTasks = 0
        val failedAuthors = mutableListOf<String>()
        profiles.forEach { profile ->
            runCatching {
                creatorBatchCoordinator.cancelForCreator(profile.key)
                if (profile.archived) {
                    creatorRepository.deleteCreatorRecords(profile)
                    deletedArchives += 1
                } else {
                    val authorTasks = taskRepository.listForAuthor(profile.key)
                    if (
                        authorTasks.any { it.storageMode != StorageMode.SAF } &&
                        !StorageInspector.hasAllFilesAccess()
                    ) {
                        error("缺少所有文件访问权限")
                    }
                    val specs = authorTasks.mapNotNull { taskRepository.getSpec(it.id) }
                    val failures = authorTasks.count { task ->
                        !deletionCoordinator.deleteTask(task.id, deleteFiles = true).success
                    }
                    if (failures > 0) error("$failures 个任务未能删除")
                    specs.forEach(taskFolderPruner::pruneCreatorParents)
                    creatorRepository.deleteCreatorRecords(profile)
                    deletedAuthors += 1
                    deletedTasks += authorTasks.size
                }
            }.onFailure { error ->
                failedAuthors += "${profile.nickname}：${Redactor.sanitize(error.message ?: "删除失败")}"
            }
        }
        logger.event("creator-bulk-delete", "CREATOR", "CREATORS_DELETE_COMPLETE", JSONObject().apply {
            put("selected", profiles.size)
            put("authors_deleted", deletedAuthors)
            put("archives_deleted", deletedArchives)
            put("tasks_deleted", deletedTasks)
            put("failed", failedAuthors.size)
        })
        return CreatorDeleteResult(
            deletedAuthors = deletedAuthors,
            deletedArchives = deletedArchives,
            deletedTasks = deletedTasks,
            failedAuthors = failedAuthors,
        )
    }

    private fun refreshEstimate() {
        val current = _state.value
        viewModelScope.launch {
            val works = creatorRepository.getWorks(current.selectedWorkKeys)
            val estimate = estimateBatchSize(works, _state.value.batchSettings.quality)
            val capacity = if (customTreeUri.isNullOrBlank()) inspector.defaultStorageCapacity() else null
            _state.update {
                it.copy(
                    estimate = estimate,
                    availableBytes = capacity?.first,
                    totalBytes = capacity?.second,
                )
            }
        }
    }

    private fun notify(message: String) {
        messageChannel.trySend(message)
    }

    private companion object {
        const val CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1_000L
        const val MANUAL_REFRESH_COOLDOWN_MS = 2_500L
        const val RISK_SELECTION_THRESHOLD = 30
        const val CREATOR_PAGE_SIZE = 20
        val XHS_CREATOR_WEB_FALLBACK_ERRORS = setOf("DETAIL_EMPTY", "CREATOR_PAGE_EMPTY")
    }
}

private data class CreatorDeleteResult(
    val deletedAuthors: Int,
    val deletedArchives: Int,
    val deletedTasks: Int,
    val failedAuthors: List<String>,
) {
    val message: String
        get() = buildList {
            if (deletedAuthors > 0) add("彻底删除 $deletedAuthors 位作者、$deletedTasks 个任务")
            if (deletedArchives > 0) add("移除 $deletedArchives 条归档记录，本地任务和文件已保留")
            if (failedAuthors.isNotEmpty()) {
                add("${failedAuthors.size} 位删除失败：${failedAuthors.take(2).joinToString("；")}")
            }
        }.joinToString("；").ifBlank { "没有可删除的作者" }
}
