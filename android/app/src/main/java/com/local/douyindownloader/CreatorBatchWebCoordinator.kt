package com.local.douyindownloader

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Singleton
internal class CreatorBatchWebCoordinator @Inject constructor(
    private val creators: CreatorLibraryRepository,
    private val batches: DownloadBatchDao,
    private val parser: ParserGateway,
    private val settingsRepository: SettingsRepository,
    private val taskPreparer: CreatorBatchTaskPreparer,
    private val logger: DiagnosticLogger,
) {
    suspend fun complete(
        request: CreatorBatchWebRequest,
        cookieHeader: String,
        readySource: CookieReadySource,
        snapshot: WebPageSnapshot?,
    ): CreatorWebPreparationOutcome {
        val batch = batches.getBatch(request.batchId)
            ?: return CreatorWebPreparationOutcome("批量任务已不存在")
        if (batch.status != CreatorBatchStatus.WAITING_FOREGROUND) {
            return CreatorWebPreparationOutcome()
        }
        val profile = creators.getCreator(batch.creatorKey)
            ?: return CreatorWebPreparationOutcome("作者记录已不存在")
        val before = batches.getWork(request.batchId, request.workKey)
            ?: return CreatorWebPreparationOutcome("作品批量记录已不存在")
        if (before.status != CreatorBatchWorkStatus.WEB_REQUIRED) {
            return CreatorWebPreparationOutcome()
        }
        if (batches.markWebParsing(request.batchId, request.workKey) == 0) {
            return CreatorWebPreparationOutcome()
        }
        val entry = batches.getWork(request.batchId, request.workKey) ?: before
        val indexedWork = creators.getWorks(listOf(request.workKey)).firstOrNull()
        if (indexedWork == null) {
            batches.updateWork(
                request.batchId,
                request.workKey,
                CreatorBatchWorkStatus.FAILED,
                "",
                "本地作品索引已丢失",
            )
            finishBatch(request.batchId)
            return CreatorWebPreparationOutcome("有作品索引已丢失，已跳过")
        }
        val sourceUrl = entry.sourceUrl.ifBlank { indexedWork.canonicalUrl }
        logger.event(request.batchId, "BATCH", "WORK_WEB_PARSE_STARTED", JSONObject().apply {
            put("work_key", request.workKey)
            put("attempt", entry.attemptCount)
            put("ready_source", readySource.name.lowercase(Locale.US))
            put("credential_present", cookieHeader.isNotBlank())
            if (profile.platform == SourcePlatform.XIAOHONGSHU) {
                put("has_xsec_token", hasXsecToken(sourceUrl))
            }
        })
        if (snapshot != null) {
            logger.event(request.batchId, "BATCH", "WORK_WEB_SNAPSHOT_READY", JSONObject().apply {
                put("work_key", request.workKey)
                put("initial_bytes", snapshot.initialData.toByteArray().size)
                put("content_bytes", snapshot.contentHtml.toByteArray().size)
                put("visible_bytes", snapshot.visibleText.toByteArray().size)
                put("final_host", safeCreatorSourceLocation(snapshot.finalUrl)["source_host"].orEmpty())
                put("final_path", safeCreatorSourceLocation(snapshot.finalUrl)["source_path"].orEmpty())
            })
        }
        val result = parseSnapshot(profile.platform, sourceUrl, cookieHeader, readySource, snapshot)
        if (result.ok) {
            val prepared = taskPreparer.prepare(
                batchId = request.batchId,
                profile = profile,
                indexedWork = indexedWork,
                sourceUrl = sourceUrl,
                result = result,
                settings = BatchDownloadSettings.fromJson(batch.settingsJson),
                appSettings = settingsRepository.current(),
            )
            logger.event(
                request.batchId,
                "BATCH",
                if (prepared.success) "WORK_WEB_PARSE_SUCCEEDED" else "WORK_WEB_PARSE_FAILED",
                JSONObject().apply {
                    put("work_key", request.workKey)
                    put("attempt", entry.attemptCount)
                    if (!prepared.success) put("message", prepared.message)
                },
            )
        } else {
            val outcome = recordFailure(profile.platform, request, entry, sourceUrl, result)
            if (outcome != null) return outcome
        }
        val finalStatus = finishBatch(request.batchId)
        return CreatorWebPreparationOutcome(
            message = if (finalStatus != CreatorBatchStatus.WAITING_FOREGROUND) {
                "批量准备完成，已开始下载可用作品"
            } else "",
        )
    }

    suspend fun pause(creatorKey: String): Boolean {
        val batch = batches.latestPaused(creatorKey) ?: return false
        if (batch.status != CreatorBatchStatus.WAITING_FOREGROUND) return false
        batches.recoverWebParsing(batch.batchId)
        batches.updateBatch(batch.batchId, CreatorBatchStatus.PAUSED)
        logger.event(batch.batchId, "BATCH", "BATCH_PREPARATION_PAUSED", JSONObject())
        return true
    }

    suspend fun recover(creatorKey: String) {
        val batch = batches.latestPaused(creatorKey) ?: return
        batches.recoverWebParsing(batch.batchId)
        val entries = batches.listWorks(batch.batchId)
        if (entries.none { it.status == CreatorBatchWorkStatus.WEB_REQUIRED } &&
            entries.any { it.status == CreatorBatchWorkStatus.PREPARED }
        ) {
            taskPreparer.submitPrepared(batch.batchId)
            batches.updateBatch(
                batch.batchId,
                batchStatusAfterPreparation(batches.listWorks(batch.batchId)),
            )
        }
    }

    private suspend fun recordFailure(
        platform: SourcePlatform,
        request: CreatorBatchWebRequest,
        entry: BatchWorkEntity,
        sourceUrl: String,
        result: ParseResult,
    ): CreatorWebPreparationOutcome? {
        val safeMessage = Redactor.sanitize(result.message)
        val loginRequired = result.errorCode in setOf("LOGIN_REQUIRED", "AUTH_OR_RISK")
        // Reloading the same signed page without refreshing its token only repeats the
        // same failure and dramatically increases preparation time and risk-control load.
        val canRetry = result.errorCode in WEB_RETRYABLE_ERRORS &&
            entry.attemptCount < MAX_WEB_ATTEMPTS
        when {
            loginRequired -> {
                batches.requireWeb(request.batchId, request.workKey, sourceUrl, safeMessage)
                batches.updateBatch(request.batchId, CreatorBatchStatus.PAUSED)
            }
            canRetry -> batches.requireWeb(request.batchId, request.workKey, sourceUrl, safeMessage)
            else -> {
                creators.updateRemoteStatus(
                    request.workKey,
                    if (result.errorCode == "CONTENT_UNAVAILABLE") {
                        CreatorWorkRemoteStatus.UNAVAILABLE
                    } else CreatorWorkRemoteStatus.CHECK_FAILED,
                )
                batches.updateWork(
                    request.batchId,
                    request.workKey,
                    CreatorBatchWorkStatus.FAILED,
                    "",
                    safeMessage,
                )
            }
        }
        logger.event(request.batchId, "BATCH", "WORK_WEB_PARSE_FAILED", JSONObject().apply {
            put("work_key", request.workKey)
            put("attempt", entry.attemptCount)
            put("code", result.errorCode)
            put("message", safeMessage)
            put("will_retry", canRetry)
            put("login_required", loginRequired)
        })
        return if (loginRequired) {
            CreatorWebPreparationOutcome(
                message = "${platform.displayName}页面要求登录，请完成登录后再继续准备",
                loginRequired = true,
            )
        } else null
    }

    private suspend fun finishBatch(batchId: String): String {
        if (batches.getBatch(batchId)?.status == CreatorBatchStatus.PAUSED) {
            return CreatorBatchStatus.PAUSED
        }
        var entries = batches.listWorks(batchId)
        if (entries.none { it.status == CreatorBatchWorkStatus.WEB_REQUIRED }) {
            taskPreparer.submitPrepared(batchId)
            entries = batches.listWorks(batchId)
        }
        val status = batchStatusAfterPreparation(entries)
        batches.updateBatch(batchId, status)
        if (status != CreatorBatchStatus.WAITING_FOREGROUND) {
            logger.event(batchId, "BATCH", "BATCH_PREPARATION_COMPLETE", JSONObject().apply {
                put("scheduled", entries.count { it.status == CreatorBatchWorkStatus.SCHEDULED })
                put("failed", entries.count { it.status == CreatorBatchWorkStatus.FAILED })
            })
        }
        return status
    }

    private suspend fun parseSnapshot(
        platform: SourcePlatform,
        sourceUrl: String,
        cookieHeader: String,
        readySource: CookieReadySource,
        snapshot: WebPageSnapshot?,
    ): ParseResult = if (snapshot == null) {
        ParseResult(
            ok = false,
            platform = platform,
            errorCode = if (readySource == CookieReadySource.TIMEOUT) "PAGE_TIMEOUT" else "DETAIL_EMPTY",
            message = if (readySource == CookieReadySource.TIMEOUT) {
                "${platform.displayName}页面准备超时"
            } else "${platform.displayName}页面没有返回作品状态",
        )
    } else {
        try {
            parser.parse(sourceUrl, cookieHeader, snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ParseResult(
                ok = false,
                platform = platform,
                errorCode = "PARSE_FAILED",
                message = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private companion object {
        val WEB_RETRYABLE_ERRORS = setOf("DETAIL_EMPTY", "URL_RESOLVE_FAILED", "PAGE_TIMEOUT")
        const val MAX_WEB_ATTEMPTS = 1
    }
}
