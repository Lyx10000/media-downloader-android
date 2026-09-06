package com.local.douyindownloader

internal object CreatorBatchStatus {
    const val QUEUED = "QUEUED"
    const val WAITING_FOREGROUND = "WAITING_FOREGROUND"
    const val PAUSED = "PAUSED"
    const val SCHEDULED = "SCHEDULED"
    const val FAILED = "FAILED"
}

internal object CreatorBatchWorkStatus {
    const val QUEUED = "QUEUED"
    const val PARSING = "PARSING"
    const val WEB_REQUIRED = "WEB_REQUIRED"
    const val PREPARED = "PREPARED"
    const val SCHEDULED = "SCHEDULED"
    const val FAILED = "FAILED"
    const val PAUSED = "PAUSED"
}

data class CreatorBatchPreparation(
    val batchId: String,
    val creatorKey: String,
    val status: String,
    val total: Int,
    val processed: Int,
    val pendingWorkKey: String = "",
    val pendingUrl: String = "",
    val pendingStatus: String = "",
    val attemptCount: Int = 0,
    val lastError: String = "",
) {
    val needsForeground: Boolean
        get() = status == CreatorBatchStatus.WAITING_FOREGROUND && pendingWorkKey.isNotBlank()

    val isPaused: Boolean
        get() = status == CreatorBatchStatus.PAUSED && pendingWorkKey.isNotBlank()

    val webRequest: CreatorBatchWebRequest?
        get() = takeIf {
            needsForeground && pendingStatus == CreatorBatchWorkStatus.WEB_REQUIRED &&
                pendingUrl.isNotBlank()
        }?.let {
            CreatorBatchWebRequest(
                batchId = batchId,
                workKey = pendingWorkKey,
                url = pendingUrl,
                position = (processed + 1).coerceAtMost(total.coerceAtLeast(1)),
                total = total,
                attemptCount = attemptCount,
            )
        }
}

data class CreatorBatchWebRequest(
    val batchId: String,
    val workKey: String,
    val url: String,
    val position: Int,
    val total: Int,
    val attemptCount: Int,
)

internal fun shouldUseCreatorWebFallback(
    platform: SourcePlatform,
    kind: MediaKind,
    errorCode: String,
): Boolean = when (platform) {
    SourcePlatform.XIAOHONGSHU -> errorCode in setOf(
        "DETAIL_EMPTY",
        "URL_RESOLVE_FAILED",
        "LOGIN_REQUIRED",
    )
    SourcePlatform.ZHIHU -> kind == MediaKind.DOCUMENT && errorCode in setOf(
        "AUTH_OR_RISK",
        "DETAIL_EMPTY",
        "LOGIN_REQUIRED",
    )
    SourcePlatform.DOUYIN -> false
}

internal fun batchStatusAfterPreparation(entries: List<BatchWorkEntity>): String = when {
    entries.any { it.status == CreatorBatchWorkStatus.WEB_REQUIRED } ->
        CreatorBatchStatus.WAITING_FOREGROUND
    entries.any { it.status == CreatorBatchWorkStatus.PAUSED } -> CreatorBatchStatus.PAUSED
    entries.any { it.status == CreatorBatchWorkStatus.SCHEDULED } -> CreatorBatchStatus.SCHEDULED
    else -> CreatorBatchStatus.FAILED
}

data class CreatorWebPreparationOutcome(
    val message: String = "",
    val loginRequired: Boolean = false,
)

internal fun hasXsecToken(url: String): Boolean = runCatching {
    java.net.URI(url).rawQuery.orEmpty().split('&').any { parameter ->
        parameter.substringBefore('=').equals("xsec_token", ignoreCase = true)
    }
}.getOrDefault(false)
