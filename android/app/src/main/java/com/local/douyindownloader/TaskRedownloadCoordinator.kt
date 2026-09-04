package com.local.douyindownloader

import androidx.work.ExistingWorkPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

data class TaskRedownloadResult(
    val success: Boolean,
    val message: String,
)

internal enum class RedownloadCredential(val wireValue: String) {
    ANONYMOUS("anonymous"),
    STORED_COOKIE("stored_cookie"),
}

internal fun redownloadCredentialPlan(
    platform: SourcePlatform,
    hasStoredCookie: Boolean,
): List<RedownloadCredential> = when (platform) {
    SourcePlatform.XIAOHONGSHU -> buildList {
        add(RedownloadCredential.ANONYMOUS)
        add(RedownloadCredential.ANONYMOUS)
        if (hasStoredCookie) add(RedownloadCredential.STORED_COOKIE)
    }
    SourcePlatform.DOUYIN -> List(3) {
        if (hasStoredCookie) RedownloadCredential.STORED_COOKIE else RedownloadCredential.ANONYMOUS
    }
    SourcePlatform.ZHIHU -> listOf(
        if (hasStoredCookie) RedownloadCredential.STORED_COOKIE else RedownloadCredential.ANONYMOUS,
    )
}

internal fun isRetriableRedownloadParseFailure(errorCode: String): Boolean = errorCode in setOf(
    "AUTH_OR_RISK",
    "DETAIL_EMPTY",
    "LOGIN_REQUIRED",
    "NETWORK",
    "HTTP_ERROR",
)

internal fun hasReusableDownloadSources(result: ParseResult): Boolean = when (result.kind) {
    MediaKind.VIDEO -> result.variants.any { it.urls.isNotEmpty() }
    MediaKind.IMAGE -> result.imageCandidates.any { it.isNotEmpty() } || result.imageUrls.isNotEmpty()
    MediaKind.DOCUMENT -> result.document != null
}

private data class RedownloadParseResolution(
    val result: ParseResult,
    val usedStoredSources: Boolean,
    val attempts: Int,
    val lastFailure: ParseResult? = null,
)

@Singleton
internal class TaskRedownloadCoordinator @Inject constructor(
    private val repository: DownloadTaskRepository,
    private val parser: ParserGateway,
    private val inspector: StorageInspector,
    private val sourceValidator: RedownloadSourceValidator,
    private val scheduler: DownloadScheduler,
    private val logger: DiagnosticLogger,
) {
    suspend fun retry(
        task: TaskRecord,
        cookieHeader: String,
        customTreeUri: String?,
    ): TaskRedownloadResult {
        val originalSpec = repository.getSpec(task.id)
            ?: return failure("旧任务缺少作品 ID，无法自动重新解析")
        if (originalSpec.result.contentId.isBlank() && originalSpec.stableSource().isBlank()) {
            return failure("旧任务缺少作品 ID，无法自动重新解析")
        }
        val resolvedStorage = resolveStorage(originalSpec, customTreeUri)
            ?: return failure("保存目录已失效，请重新选择目录后再下载")

        repository.update(task.id, TaskStatus.RUNNING, "正在重新解析作品", 0)
        logger.event(task.id, "REDOWNLOAD", "REPARSE_STARTED", JSONObject().apply {
            put("content_id", originalSpec.result.contentId)
            put("platform", originalSpec.result.platform.wireValue)
            put("kind", originalSpec.result.kind.wireValue)
        })
        val resolution = resolveDownloadSources(originalSpec, cookieHeader, task.id)
        val refreshed = resolution.result
        if (!refreshed.ok) {
            val hint = if (refreshed.errorCode in setOf(
                    "AUTH_OR_RISK",
                    "DETAIL_EMPTY",
                    "LOGIN_REQUIRED",
                )
            ) {
                "请返回首页，点击${task.platform.displayName}登录状态后重试"
            } else {
                refreshed.message
            }
            repository.update(task.id, TaskStatus.FAILED, "重新解析失败", 0, hint)
            logger.event(task.id, "REDOWNLOAD", "REPARSE_FAILED", JSONObject().apply {
                put("code", refreshed.errorCode)
                put("message", refreshed.message)
                put("attempts", resolution.attempts)
                put("stored_sources_available", hasReusableDownloadSources(originalSpec.result))
            })
            return failure(hint)
        }

        val previous = originalSpec.result.variants.getOrNull(originalSpec.variantIndex)
        val match = if (refreshed.kind == MediaKind.VIDEO) {
            matchVariant(previous, refreshed.variants)
        } else {
            VariantMatch(0, true)
        }
        if (refreshed.kind == MediaKind.VIDEO && match.index < 0) {
            repository.update(
                task.id,
                TaskStatus.FAILED,
                "重新解析失败",
                0,
                "当前没有可下载的视频档位",
            )
            return failure("当前没有可下载的视频档位")
        }

        val now = System.currentTimeMillis()
        val selectedVariant = match.index.coerceAtLeast(0)
        val preflight = sourceValidator.validate(refreshed, selectedVariant, originalSpec.mode)
        logger.event(task.id, "REDOWNLOAD", "SOURCE_PREFLIGHT_COMPLETE", JSONObject().apply {
            put("required_groups", preflight.requiredGroups)
            put("verified_groups", preflight.verifiedGroups)
            put("all_verified", preflight.allVerified)
        })
        val currentTask = repository.get(task.id)
            ?: return failure("任务已被删除，无法开始重新下载")
        val updatedSpec = originalSpec.copy(
            pendingRedownload = PendingRedownload(
                result = refreshed,
                variantIndex = selectedVariant,
                storageMode = resolvedStorage.first,
                storageRoot = resolvedStorage.second,
                taskFolder = redownloadTaskFolderName(now, task.id),
                previousOutputs = currentTask.outputs,
                previousFileState = currentTask.fileState,
            ),
        )
        repository.replaceSpec(task.id, updatedSpec)
        repository.update(task.id, TaskStatus.QUEUED, "等待重新下载", 0)
        logger.event(task.id, "REDOWNLOAD", "REPARSE_COMPLETE", JSONObject().apply {
            put("variant", match.index)
            put("exact_match", match.exact)
            put("images", refreshed.imageCandidates.size)
            put("attempts", resolution.attempts)
            put("used_stored_sources", resolution.usedStoredSources)
            put("last_failure_code", resolution.lastFailure?.errorCode.orEmpty())
            put("preflight_all_verified", preflight.allVerified)
            put("author_present", refreshed.author.isNotBlank())
            put("author_account_id_present", refreshed.authorAccountId.isNotBlank())
        })
        try {
            scheduler.enqueue(task.id, ExistingWorkPolicy.REPLACE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            repository.replaceSpec(task.id, originalSpec.copy(pendingRedownload = null))
            repository.update(task.id, TaskStatus.FAILED, "启动重新下载失败", 0, message)
            return failure("启动重新下载失败：$message")
        }
        val message = if (resolution.usedStoredSources) {
            "平台重新解析受限，已使用任务保存的原资源地址重试下载"
        } else if (refreshed.kind == MediaKind.VIDEO && !match.exact) {
            if (previous == null) "已选择当前可获得的最高档位"
            else "原清晰度已不可用，已选择当前最接近的档位"
        } else {
            "已重新解析并开始下载"
        }
        return TaskRedownloadResult(true, message)
    }

    private fun failure(message: String) = TaskRedownloadResult(false, message)

    private suspend fun resolveDownloadSources(
        originalSpec: TaskSpec,
        cookieHeader: String,
        taskId: String,
    ): RedownloadParseResolution {
        val source = originalSpec.stableSource()
        val attempts = redownloadCredentialPlan(
            originalSpec.result.platform,
            hasStoredCookie = cookieHeader.isNotBlank(),
        )
        var lastFailure: ParseResult? = null
        var attempted = 0
        for ((index, credential) in attempts.withIndex()) {
            attempted += 1
            logger.event(taskId, "REDOWNLOAD", "REPARSE_ATTEMPT_STARTED", JSONObject().apply {
                put("attempt", attempted)
                put("max_attempts", attempts.size)
                put("credential", credential.wireValue)
            })
            val result = parser.parse(
                source,
                if (credential == RedownloadCredential.STORED_COOKIE) cookieHeader else "",
            )
            if (result.ok) {
                return RedownloadParseResolution(
                    result = mergeStableMetadata(result, originalSpec.result),
                    usedStoredSources = false,
                    attempts = attempted,
                    lastFailure = lastFailure,
                )
            }
            lastFailure = result
            logger.event(taskId, "REDOWNLOAD", "REPARSE_ATTEMPT_FAILED", JSONObject().apply {
                put("attempt", attempted)
                put("credential", credential.wireValue)
                put("code", result.errorCode)
                put("message", result.message)
            })
            if (!isRetriableRedownloadParseFailure(result.errorCode)) break
            if (index < attempts.lastIndex) delay(REPARSE_RETRY_DELAYS_MS[index.coerceAtMost(REPARSE_RETRY_DELAYS_MS.lastIndex)])
        }
        if (hasReusableDownloadSources(originalSpec.result)) {
            logger.event(taskId, "REDOWNLOAD", "STORED_SOURCES_FALLBACK", JSONObject().apply {
                put("attempts", attempted)
                put("last_code", lastFailure?.errorCode.orEmpty())
                put("kind", originalSpec.result.kind.wireValue)
            })
            return RedownloadParseResolution(
                result = originalSpec.result,
                usedStoredSources = true,
                attempts = attempted,
                lastFailure = lastFailure,
            )
        }
        return RedownloadParseResolution(
            result = lastFailure ?: ParseResult(
                ok = false,
                platform = originalSpec.result.platform,
                errorCode = "REPARSE_FAILED",
                message = "重新解析没有返回可用资源",
            ),
            usedStoredSources = false,
            attempts = attempted,
            lastFailure = lastFailure,
        )
    }

    private fun mergeStableMetadata(fresh: ParseResult, stored: ParseResult): ParseResult = fresh.copy(
        author = fresh.author.ifBlank { stored.author },
        authorAccountId = fresh.authorAccountId.ifBlank { stored.authorAccountId },
        description = fresh.description.ifBlank { stored.description },
        coverUrl = fresh.coverUrl.ifBlank { stored.coverUrl },
    )

    private fun resolveStorage(
        spec: TaskSpec,
        customTreeUri: String?,
    ): Pair<StorageMode, String>? = when (spec.storageMode) {
        StorageMode.SAF -> {
            if (inspector.isTreeAvailable(spec.storageRoot)) {
                StorageMode.SAF to spec.storageRoot
            } else if (!customTreeUri.isNullOrBlank() && inspector.isTreeAvailable(customTreeUri)) {
                StorageMode.SAF to customTreeUri
            } else {
                null
            }
        }
        StorageMode.DEFAULT -> StorageMode.DEFAULT to ""
        StorageMode.LEGACY -> {
            if (!customTreeUri.isNullOrBlank() && inspector.isTreeAvailable(customTreeUri)) {
                StorageMode.SAF to customTreeUri
            } else {
                StorageMode.DEFAULT to ""
            }
        }
    }

    companion object {
        private val REPARSE_RETRY_DELAYS_MS = longArrayOf(350L, 800L)
    }
}
