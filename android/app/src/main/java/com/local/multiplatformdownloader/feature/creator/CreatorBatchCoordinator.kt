package com.local.multiplatformdownloader.feature.creator


import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.logging.Redactor
import com.local.multiplatformdownloader.core.download.AdaptiveDownloadController
import com.local.multiplatformdownloader.core.download.WorkloadAdmissionGate
import com.local.multiplatformdownloader.core.model.FileState
import com.local.multiplatformdownloader.core.model.ParseResult
import com.local.multiplatformdownloader.core.model.ParserAttempt
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.core.settings.BatchDownloadSettings
import com.local.multiplatformdownloader.feature.home.CookieReadySource
import com.local.multiplatformdownloader.core.settings.SettingsRepository
import com.local.multiplatformdownloader.feature.tasks.TaskRedownloadCoordinator
import com.local.multiplatformdownloader.feature.tasks.TaskRedownloadResult
import com.local.multiplatformdownloader.platform.common.ParserGateway

import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import java.text.SimpleDateFormat
import java.net.URI
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CreatorBatchStartResult(
    val batchId: String,
    val started: Int,
    val failed: Int,
    val paused: Int,
    val foregroundRequired: Int = 0,
) {
    val message: String = buildList {
        if (started > 0) add("已创建包含 $started 个作品的后台批次")
        if (failed > 0) add("$failed 个解析失败")
        if (paused > 0) add("$paused 个因风控暂停")
        if (foregroundRequired > 0) add("$foregroundRequired 个需要保持前台继续准备")
    }.joinToString("，").ifBlank { "没有可下载的作品" }
}

@Singleton
class CreatorBatchCoordinator @Inject internal constructor(
    private val creators: CreatorLibraryRepository,
    private val batches: DownloadBatchDao,
    private val parser: ParserGateway,
    private val creatorSources: CreatorSourceRouter,
    private val settingsRepository: SettingsRepository,
    private val redownloadCoordinator: TaskRedownloadCoordinator,
    private val taskPreparer: CreatorBatchTaskPreparer,
    private val webCoordinator: CreatorBatchWebCoordinator,
    private val logger: DiagnosticLogger,
    private val workManager: WorkManager,
    private val adaptiveDownloadController: AdaptiveDownloadController,
    private val workloadAdmissionGate: WorkloadAdmissionGate,
) {
    suspend fun start(
        profile: CreatorProfile,
        selectedWorkKeys: Set<String>,
        settings: BatchDownloadSettings,
    ): CreatorBatchStartResult {
        if (profile.platform !in CREATOR_BATCH_PLATFORMS) {
            throw IllegalArgumentException("小红书作者批量下载已停止支持")
        }
        val works = creators.getWorks(selectedWorkKeys).filter { it.creatorKey == profile.key }
        if (works.isEmpty()) return CreatorBatchStartResult("", 0, 0, 0)
        val batchId = UUID.randomUUID().toString()
        batches.upsertBatch(
            DownloadBatchEntity(
                batchId = batchId,
                creatorKey = profile.key,
                createdAt = System.currentTimeMillis(),
                status = "QUEUED",
                settingsJson = settings.toJson(),
                selectedCount = works.size,
            ),
        )
        batches.upsertWorks(works.map { work ->
            BatchWorkEntity(
                batchId = batchId,
                workKey = work.key,
                status = CreatorBatchWorkStatus.QUEUED,
                taskId = "",
                error = "",
                sourceUrl = work.canonicalUrl,
            )
        })
        settingsRepository.setBatchDownloadSettings(settings)
        val request = OneTimeWorkRequestBuilder<CreatorBatchWorker>()
            .setInputData(Data.Builder().putString(CreatorBatchWorker.KEY_BATCH_ID, batchId).build())
            .addTag(batchId)
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(batchId), ExistingWorkPolicy.KEEP, request).await()
        // Only retire old inactive records after the replacement is durably enqueued.
        // Keep the new batch even if it already hit a risk response before this call resumes.
        batches.deleteInactivePreparations(works.map(CreatorWork::key), retainedBatchId = batchId)
        logger.event(batchId, "BATCH", "BATCH_QUEUED", JSONObject().apply {
            put("creator_key", profile.key)
            put("selected", works.size)
            put("work_id", request.id.toString())
        })
        return CreatorBatchStartResult(batchId, works.size, 0, 0)
    }

    suspend fun execute(
        batchId: String,
        cookieHeader: String,
        requestedWorkKey: String = "",
        bypassRiskCooldown: Boolean = false,
    ): CreatorBatchStartResult = workloadAdmissionGate.withBatchPreparationPermit {
        executeAdmitted(batchId, cookieHeader, requestedWorkKey, bypassRiskCooldown)
    }

    private suspend fun executeAdmitted(
        batchId: String,
        cookieHeader: String,
        requestedWorkKey: String,
        bypassRiskCooldown: Boolean,
    ): CreatorBatchStartResult {
        val batch = batches.getBatch(batchId)
            ?: return CreatorBatchStartResult(batchId, 0, 1, 0)
        val profile = creators.getCreator(batch.creatorKey)
            ?: return CreatorBatchStartResult(batchId, 0, 1, 0)
        if (profile.platform !in CREATOR_BATCH_PLATFORMS) {
            batches.listWorks(batchId).forEach { entry ->
                batches.updateWork(
                    batchId,
                    entry.workKey,
                    CreatorBatchWorkStatus.FAILED,
                    entry.taskId,
                    "该平台的作者批量下载已停止支持",
                )
            }
            batches.updateBatch(batchId, CreatorBatchStatus.FAILED)
            return CreatorBatchStartResult(batchId, 0, 1, 0)
        }
        val settings = BatchDownloadSettings.fromJson(batch.settingsJson)
        if (requestedWorkKey.isBlank()) {
            batches.recoverWebParsing(batchId)
            batches.recoverHttpPreparation(batchId)
        }
        val queuedEntries = batches.listWorks(batchId).filter {
            it.status in setOf(CreatorBatchWorkStatus.QUEUED, CreatorBatchWorkStatus.PAUSED) &&
                (requestedWorkKey.isBlank() || it.workKey == requestedWorkKey)
        }
        val riskUntil = adaptiveDownloadController.platformRiskUntilAfterLoad(profile.platform)
        if (!bypassRiskCooldown && riskUntil > System.currentTimeMillis()) {
            queuedEntries.forEach { entry ->
                batches.updateWork(
                    batchId,
                    entry.workKey,
                    CreatorBatchWorkStatus.PAUSED,
                    entry.taskId,
                    "平台刚触发风控，等待冷却后继续",
                )
            }
            batches.updateBatch(batchId, CreatorBatchStatus.PAUSED)
            scheduleAutomaticResume(batchId, riskUntil)
            logger.event(batchId, "BATCH", "PLATFORM_CIRCUIT_BLOCKED_PREPARATION", JSONObject().apply {
                put("platform", profile.platform.wireValue)
                put("paused", queuedEntries.size)
                put("risk_until_ms", riskUntil)
                put("auto_resume", true)
            })
            return CreatorBatchStartResult(batchId, 0, 0, queuedEntries.size)
        }
        val worksByKey = creators.getWorks(queuedEntries.map(BatchWorkEntity::workKey))
            .associateBy(CreatorWork::key)
        val appSettings = settingsRepository.current()

        var started = 0
        var failed = 0
        var paused = 0
        var foregroundRequired = 0
        var riskTriggered = false
        var triggeredRiskUntil = 0L
        var consecutiveXiaohongshuDetailFailures = 0
        var refreshedXiaohongshuWorks: Map<String, CreatorWork>? = null
        queuedEntries.forEach { entry ->
            // A user may have deleted/replaced a paused record after this batch was loaded.
            if (batches.getWork(batchId, entry.workKey) == null) return@forEach
            val work = worksByKey[entry.workKey]
            if (work == null) {
                failed += 1
                batches.updateWork(
                    batchId,
                    entry.workKey,
                    CreatorBatchWorkStatus.FAILED,
                    "",
                    "本地作品索引已丢失",
                )
                return@forEach
            }
            if (riskTriggered) {
                paused += 1
                batches.updateWork(
                    batchId,
                    work.key,
                    CreatorBatchWorkStatus.PAUSED,
                    "",
                    "等待用户稍后继续",
                )
                return@forEach
            }
            // Atomically claim the row: deletion/replacement of inactive rows and parsing
            // cannot both win. A resumed item must stop advertising itself as paused.
            if (batches.claimHttpPreparation(batchId, work.key) == 0) return@forEach
            val existing = work.task
            if (existing != null && existing.fileState == FileState.AVAILABLE && profile.platform != SourcePlatform.BILIBILI) {
                val retry = runCatching {
                    redownloadCoordinator.retry(
                        existing,
                        cookieHeader,
                        appSettings.customTreeUri,
                        settings,
                    )
                }.getOrElse { TaskRedownloadResult(false, it.message ?: "重新下载失败") }
                if (retry.success) {
                    started += 1
                    batches.updateWork(
                        batchId,
                        work.key,
                        CreatorBatchWorkStatus.SCHEDULED,
                        existing.id,
                        "",
                    )
                } else {
                    failed += 1
                    batches.updateWork(
                        batchId,
                        work.key,
                        CreatorBatchWorkStatus.FAILED,
                        existing.id,
                        retry.message,
                    )
                }
                return@forEach
            }

            var activeWork = refreshedXiaohongshuWorks?.get(work.key) ?: work
            if (profile.platform == SourcePlatform.XIAOHONGSHU) {
                val preferredUrl = preferredXiaohongshuCreatorWorkUrl(
                    activeWork.contentId,
                    activeWork.canonicalUrl,
                )
                if (preferredUrl != activeWork.canonicalUrl) {
                    activeWork = activeWork.copy(canonicalUrl = preferredUrl)
                    batches.updateWorkSource(batchId, work.key, preferredUrl)
                }
            }
            logger.event(batchId, "BATCH", "WORK_HTTP_PARSE_STARTED", JSONObject().apply {
                put("work_key", work.key)
                put("kind", work.kind.wireValue)
                put("credential_present", cookieHeader.isNotBlank())
                if (profile.platform == SourcePlatform.XIAOHONGSHU) {
                    put("has_xsec_token", hasXsecToken(activeWork.canonicalUrl))
                }
            })
            var result = try {
                parser.parse(activeWork.canonicalUrl, cookieHeader)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ParseResult(
                    ok = false,
                    platform = profile.platform,
                    errorCode = "PARSE_FAILED",
                    message = error.message ?: error.javaClass.simpleName,
                )
            }
            if (
                profile.platform == SourcePlatform.XIAOHONGSHU &&
                result.errorCode in XHS_REFRESHABLE_ERRORS
            ) {
                if (refreshedXiaohongshuWorks == null) {
                    logger.event(batchId, "BATCH", "CREATOR_PAGE_REFRESH_STARTED", JSONObject().apply {
                        put("platform", profile.platform.wireValue)
                        put("reason", result.errorCode)
                    })
                    refreshedXiaohongshuWorks = try {
                        val refreshedPage = creatorSources.fetchPage(profile, "", 1, cookieHeader)
                        creators.savePage(refreshedPage)
                        logger.event(batchId, "BATCH", "CREATOR_PAGE_REFRESH_COMPLETE", JSONObject().apply {
                            put("works", refreshedPage.works.size)
                        })
                        refreshedPage.works.associateBy(CreatorWork::key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        logger.event(batchId, "BATCH", "CREATOR_PAGE_REFRESH_FAILED", JSONObject().apply {
                            put("type", error.javaClass.name)
                            put("message", Redactor.sanitize(error.message.orEmpty()))
                        })
                        emptyMap()
                    }
                }
                refreshedXiaohongshuWorks?.get(work.key)?.let { refreshedWork ->
                    activeWork = refreshedWork.copy(
                        canonicalUrl = preferredXiaohongshuCreatorWorkUrl(
                            refreshedWork.contentId,
                            refreshedWork.canonicalUrl,
                        ),
                    )
                    batches.updateWorkSource(batchId, work.key, activeWork.canonicalUrl)
                    result = try {
                        parser.parse(activeWork.canonicalUrl, cookieHeader)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        ParseResult(
                            ok = false,
                            platform = profile.platform,
                            errorCode = "PARSE_FAILED",
                            message = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            }
            if (!result.ok) {
                if (profile.platform == SourcePlatform.XIAOHONGSHU &&
                    result.errorCode == "DETAIL_EMPTY"
                ) {
                    consecutiveXiaohongshuDetailFailures += 1
                    if (consecutiveXiaohongshuDetailFailures >= XHS_DETAIL_FAILURE_CIRCUIT_LIMIT) {
                        riskTriggered = true
                        logger.event(batchId, "BATCH", "DETAIL_FAILURE_CIRCUIT_OPENED", JSONObject().apply {
                            put("failures", consecutiveXiaohongshuDetailFailures)
                            put("remaining_will_pause", true)
                        })
                    }
                } else {
                    consecutiveXiaohongshuDetailFailures = 0
                }
                val safeMessage = Redactor.sanitize(result.message)
                logger.event(batchId, "BATCH", "WORK_HTTP_PARSE_FAILED", JSONObject().apply {
                    put("work_key", work.key)
                    put("code", result.errorCode)
                    put("message", safeMessage)
                    put("credential_present", cookieHeader.isNotBlank())
                    if (profile.platform == SourcePlatform.XIAOHONGSHU) {
                        put("has_xsec_token", hasXsecToken(activeWork.canonicalUrl))
                    }
                })
                if (shouldUseCreatorWebFallback(profile.platform, activeWork.kind, result.errorCode)) {
                    batches.requireWeb(
                        batchId = batchId,
                        workKey = work.key,
                        sourceUrl = activeWork.canonicalUrl,
                        error = safeMessage,
                    )
                    foregroundRequired += 1
                    logger.event(batchId, "BATCH", "WORK_WEB_REQUIRED", JSONObject().apply {
                        put("work_key", work.key)
                        put("code", result.errorCode)
                        put("platform", profile.platform.wireValue)
                        if (profile.platform == SourcePlatform.XIAOHONGSHU) {
                            put("has_xsec_token", hasXsecToken(activeWork.canonicalUrl))
                        }
                    })
                    return@forEach
                }
                creators.updateRemoteStatus(
                    work.key,
                    if (result.errorCode == "CONTENT_UNAVAILABLE") {
                        CreatorWorkRemoteStatus.UNAVAILABLE
                    } else CreatorWorkRemoteStatus.CHECK_FAILED,
                )
                val httpStatuses = result.parserAttempts.map(ParserAttempt::statusCode)
                val platformRiskFailure = result.errorCode in AdaptiveDownloadController.RISK_CODES ||
                    httpStatuses.any { it in AdaptiveDownloadController.RISK_HTTP_STATUSES }
                val retryAfterCooldown = bypassRiskCooldown && platformRiskFailure
                batches.updateWork(
                    batchId,
                    work.key,
                    if (retryAfterCooldown) CreatorBatchWorkStatus.PAUSED else CreatorBatchWorkStatus.FAILED,
                    "",
                    safeMessage,
                )
                if (retryAfterCooldown) paused += 1 else failed += 1
                logger.event(batchId, "BATCH", "WORK_PARSE_FAILED", JSONObject().apply {
                    put("work_key", work.key)
                    put("kind", work.kind.wireValue)
                    safeCreatorSourceLocation(activeWork.canonicalUrl).forEach { (key, value) ->
                        put(key, value)
                    }
                    put("code", result.errorCode)
                    put("message", safeMessage)
                    put("http_statuses", org.json.JSONArray().apply {
                        httpStatuses
                            .filter { it > 0 }
                            .distinct()
                            .forEach(::put)
                    })
                })
                if (result.errorCode in setOf("AUTH_OR_RISK", "LOGIN_REQUIRED", "RATE_LIMITED", "BILIBILI_RISK")) {
                    riskTriggered = true
                }
                triggeredRiskUntil = adaptiveDownloadController.reportPlatformRiskAndGetUntil(
                    profile.platform,
                    result.errorCode,
                    httpStatuses,
                )
                return@forEach
            }

            adaptiveDownloadController.reportPlatformSuccess(profile.platform)

            val prepared = taskPreparer.prepare(
                batchId = batchId,
                profile = profile,
                indexedWork = work,
                sourceUrl = activeWork.canonicalUrl,
                result = result,
                settings = settings,
                appSettings = appSettings,
                cookieHeader = cookieHeader,
            )
            if (prepared.success) {
                consecutiveXiaohongshuDetailFailures = 0
                started += 1
            } else {
                failed += 1
            }
        }
        var finalEntries = batches.listWorks(batchId)
        if (finalEntries.none { it.status == CreatorBatchWorkStatus.WEB_REQUIRED }) {
            taskPreparer.submitPrepared(batchId)
            finalEntries = batches.listWorks(batchId)
        }
        val finalStatus = batchStatusAfterPreparation(finalEntries)
        batches.updateBatch(batchId, finalStatus)
        if (triggeredRiskUntil > System.currentTimeMillis() &&
            finalEntries.any { it.status == CreatorBatchWorkStatus.PAUSED }
        ) {
            scheduleAutomaticResume(batchId, triggeredRiskUntil)
        }
        if (finalStatus == CreatorBatchStatus.WAITING_FOREGROUND) {
            logger.event(batchId, "BATCH", "BATCH_FOREGROUND_REQUIRED", JSONObject().apply {
                put("remaining", finalEntries.count { it.status == CreatorBatchWorkStatus.WEB_REQUIRED })
            })
        }
        logger.event(batchId, "BATCH", "BATCH_SUBMITTED", JSONObject().apply {
            put("creator_key", profile.key)
            put("selected", queuedEntries.size)
            put("started", started)
            put("failed", failed)
            put("paused", paused)
            put("foreground_required", foregroundRequired)
            put("quality", settings.quality.wireValue)
            put("mode", settings.mode.wireValue)
        })
        return CreatorBatchStartResult(batchId, started, failed, paused, foregroundRequired)
    }

    suspend fun completeWebPreparation(
        request: CreatorBatchWebRequest,
        cookieHeader: String,
        readySource: CookieReadySource,
        snapshot: WebPageSnapshot?,
    ): CreatorWebPreparationOutcome = webCoordinator.complete(
        request,
        cookieHeader,
        readySource,
        snapshot,
    )

    suspend fun pauseForegroundPreparation(creatorKey: String): Boolean =
        webCoordinator.pause(creatorKey)

    suspend fun recoverInterruptedPreparation(creatorKey: String) =
        webCoordinator.recover(creatorKey)

    suspend fun platformForBatch(batchId: String): SourcePlatform? {
        val batch = batches.getBatch(batchId) ?: return null
        return creators.getCreator(batch.creatorKey)?.platform
    }

    suspend fun platformRiskUntil(platform: SourcePlatform): Long =
        adaptiveDownloadController.platformRiskUntilAfterLoad(platform)

    suspend fun forceStart(batchId: String, workKey: String): String {
        val entry = batches.getWork(batchId, workKey) ?: return "该作品的准备记录已不存在"
        if (entry.status != CreatorBatchWorkStatus.PAUSED || entry.taskId.isNotBlank()) {
            return "该作品当前不能强制尝试"
        }
        // Reserve the row before enqueueing so rapid repeated taps cannot replace a
        // running force worker and strand the item in PARSING_HTTP.
        if (batches.queuePausedPreparation(batchId, workKey) == 0) {
            return "该作品已经开始处理"
        }
        val request = OneTimeWorkRequestBuilder<CreatorBatchWorker>()
            .setInputData(Data.Builder()
                .putString(CreatorBatchWorker.KEY_BATCH_ID, batchId)
                .putString(CreatorBatchWorker.KEY_WORK_KEY, workKey)
                .putBoolean(CreatorBatchWorker.KEY_BYPASS_RISK_COOLDOWN, true)
                .build())
            .addTag(batchId)
            .build()
        batches.updateBatch(batchId, CreatorBatchStatus.QUEUED)
        runCatching {
            workManager.enqueueUniqueWork(
                forceWorkName(batchId, workKey),
                ExistingWorkPolicy.KEEP,
                request,
            ).await()
        }.onFailure {
            batches.updateWork(
                batchId,
                workKey,
                CreatorBatchWorkStatus.PAUSED,
                "",
                "启动失败，可再次尝试",
            )
            throw it
        }
        logger.event(batchId, "BATCH", "WORK_FORCE_START_REQUESTED", JSONObject().apply {
            put("work_key", workKey)
        })
        return "已尝试启动所选作品"
    }

    suspend fun resume(creatorKey: String): String {
        val batch = batches.latestPaused(creatorKey)
            ?: return "没有等待继续的批次"
        val platform = creators.getCreator(batch.creatorKey)?.platform
        val riskUntil = platform?.let { adaptiveDownloadController.platformRiskUntilAfterLoad(it) } ?: 0L
        val now = System.currentTimeMillis()
        if (riskUntil > now) {
            scheduleAutomaticResume(batch.batchId, riskUntil)
            val seconds = ((riskUntil - now + 999L) / 1_000L).coerceAtLeast(1L)
            return "${platform?.displayName.orEmpty()}风控冷却中，约 ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} 后自动继续"
        }
        batches.recoverWebParsing(batch.batchId)
        val entries = batches.listWorks(batch.batchId)
        val webRequired = entries.count { it.status == CreatorBatchWorkStatus.WEB_REQUIRED }
        if (webRequired > 0) {
            val platformName = creators.getCreator(batch.creatorKey)?.platform?.displayName.orEmpty()
            batches.updateBatch(batch.batchId, CreatorBatchStatus.WAITING_FOREGROUND)
            logger.event(batch.batchId, "BATCH", "BATCH_PREPARATION_RESUMED", JSONObject().apply {
                put("remaining", webRequired)
            })
            return "已继续准备 $webRequired 个${platformName}作品，请保持应用在前台"
        }
        val paused = entries.count { it.status == CreatorBatchWorkStatus.PAUSED }
        if (paused == 0) return "没有等待继续的作品"
        batches.updateBatch(batch.batchId, CreatorBatchStatus.QUEUED)
        val request = OneTimeWorkRequestBuilder<CreatorBatchWorker>()
            .setInputData(Data.Builder().putString(CreatorBatchWorker.KEY_BATCH_ID, batch.batchId).build())
            .addTag(batch.batchId)
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(batch.batchId),
            ExistingWorkPolicy.REPLACE,
            request,
        ).await()
        return "已继续处理 $paused 个作品"
    }

    suspend fun deleteBatch(batchId: String) = withContext(Dispatchers.IO) {
        workManager.cancelAllWorkByTag(batchId).await()
        batches.deleteWorksForBatch(batchId)
        batches.deleteBatch(batchId)
        logger.event(batchId, "BATCH", "BATCH_RECORD_DELETED", JSONObject())
    }

    suspend fun cancelForCreator(creatorKey: String) = withContext(Dispatchers.IO) {
        batches.listForCreator(creatorKey).forEach { batch ->
            val workName = uniqueWorkName(batch.batchId)
            workManager.cancelUniqueWork(workName).await()
            repeat(50) {
                val active = workManager.getWorkInfosForUniqueWork(workName)
                    .get(5, TimeUnit.SECONDS)
                    .any { !it.state.isFinished }
                if (!active) return@forEach
                delay(100L)
            }
            error("无法确认作者批量任务已经停止")
        }
    }

    private suspend fun scheduleAutomaticResume(batchId: String, riskUntil: Long) {
        val autoResumeTag = autoResumeTag(batchId)
        val alreadyScheduled = workManager.getWorkInfosForUniqueWork(uniqueWorkName(batchId))
            .get(5, TimeUnit.SECONDS)
            .any { info ->
                autoResumeTag in info.tags && info.state in setOf(
                    androidx.work.WorkInfo.State.ENQUEUED,
                    androidx.work.WorkInfo.State.BLOCKED,
                )
            }
        if (alreadyScheduled) return
        val delayMs = (riskUntil - System.currentTimeMillis() + AUTO_RESUME_GRACE_MS)
            .coerceAtLeast(AUTO_RESUME_GRACE_MS)
        val request = OneTimeWorkRequestBuilder<CreatorBatchWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(CreatorBatchWorker.KEY_BATCH_ID, batchId).build())
            .addTag(batchId)
            .addTag(autoResumeTag)
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(batchId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).await()
    }

    companion object {
        private val XHS_REFRESHABLE_ERRORS = setOf("DETAIL_EMPTY", "URL_RESOLVE_FAILED")
        private const val XHS_DETAIL_FAILURE_CIRCUIT_LIMIT = 2
        private const val AUTO_RESUME_GRACE_MS = 1_000L

        fun uniqueWorkName(batchId: String): String = "creator-batch-$batchId"
        private fun autoResumeTag(batchId: String): String = "creator-batch-auto-resume-$batchId"
        private fun forceWorkName(batchId: String, workKey: String): String =
            "creator-batch-force-$batchId-${workKey.hashCode()}"
    }
}

internal fun safeCreatorSourceLocation(url: String): Map<String, String> = runCatching {
    val uri = URI(url)
    mapOf(
        "source_host" to uri.host.orEmpty(),
        "source_path" to uri.path.orEmpty(),
    )
}.getOrDefault(emptyMap())

internal fun creatorWorkFolder(
    profile: CreatorProfile,
    work: CreatorWork,
    createdAt: Long,
): String {
    val authorFolder = profile.directoryName.ifBlank { creatorDirectoryName(profile) }
    val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(
        Date(work.publishedAt.takeIf { it > 0L } ?: createdAt),
    )
    val workFolder = "${date}_${sanitizeFolderSegment(work.contentId)}"
    return "作者/${sanitizeFolderSegment(profile.platform.displayName)}/$authorFolder/$workFolder"
}

internal fun creatorDirectoryName(profile: CreatorProfile): String {
    val account = profile.accountId.ifBlank { profile.stableId.takeLast(8) }
    return listOf(
        sanitizeFolderSegment(profile.nickname.ifBlank { "未知作者" }),
        sanitizeFolderSegment(account),
        sanitizeFolderSegment(profile.stableId.takeLast(8)),
    ).joinToString("_")
}

internal fun sanitizeFolderSegment(value: String): String = value
    .replace(Regex("[\\u0000-\\u001f/\\\\:*?\"<>|]"), "_")
    .trim().trim('.')
    .take(60)
    .ifBlank { "unknown" }
