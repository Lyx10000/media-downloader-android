package com.local.multiplatformdownloader.platform.bilibili

import com.local.multiplatformdownloader.core.database.DownloadTaskRepository
import com.local.multiplatformdownloader.core.download.DownloadProgress
import com.local.multiplatformdownloader.core.download.AdaptiveDownloadController
import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.TaskSpec
import com.local.multiplatformdownloader.core.settings.BatchVideoQuality
import com.local.multiplatformdownloader.feature.creator.chooseBatchVariant

import android.webkit.CookieManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
internal class BilibiliDeferredResolver @Inject constructor(
    private val parser: BilibiliPlatformParser,
    private val tasks: DownloadTaskRepository,
    private val logger: DiagnosticLogger,
    private val adaptiveDownloadController: AdaptiveDownloadController,
) {
    private val gate = Mutex()
    private var blockedUntil = 0L
    private var lastRequestAt = 0L

    suspend fun resolve(spec: TaskSpec, progress: DownloadProgress): TaskSpec {
        if (!spec.bilibiliPending || spec.result.platform != SourcePlatform.BILIBILI) return spec
        return gate.withLock {
            val now = System.currentTimeMillis()
            check(now >= blockedUntil) { "B站暂时限制请求，请稍后手动重试该分P" }
            progress("正在解析分P资源", 0, true)
            delay((lastRequestAt + 800 - now).coerceAtLeast(0))
            val cookie = withContext(Dispatchers.Main) {
                CookieManager.getInstance().getCookie(SourcePlatform.BILIBILI.homeUrl).orEmpty()
            }
            lastRequestAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { parser.parse(spec.result.canonicalUrl, cookie, null) }
            logger.event(spec.taskId, "PARSE", "BILIBILI_PART_RESOLVED", JSONObject().apply {
                put("ok", result.ok); put("code", result.errorCode); put("content_id", spec.result.contentId)
            })
            if (!result.ok) {
                adaptiveDownloadController.reportPlatformRisk(
                    SourcePlatform.BILIBILI,
                    result.errorCode,
                    result.parserAttempts.map { it.statusCode },
                )
                if (result.errorCode in setOf("BILIBILI_RISK", "PERMISSION_DENIED", "LOGIN_REQUIRED")) {
                    blockedUntil = System.currentTimeMillis() + 60_000
                }
                error(result.message)
            }
            adaptiveDownloadController.reportPlatformSuccess(SourcePlatform.BILIBILI)
            check(result.contentId == spec.result.contentId) { "分P顺序或内容发生变化，未替换为其他分P" }
            val previous = spec.result.variants.getOrNull(spec.variantIndex)
            val eligible = result.variants.indices.filter { previous == null || it.let { i -> result.variants[i].height <= previous.height } }
                .ifEmpty { result.variants.indices.toList() }
            val selected = if (spec.bilibiliBatchQuality.isNotBlank()) {
                chooseBatchVariant(result.variants, BatchVideoQuality.fromWire(spec.bilibiliBatchQuality), true)
            } else eligible.maxByOrNull { result.variants[it].height } ?: error("该分P没有可用档位")
            if (previous != null && result.variants[selected].height != previous.height) {
                logger.event(spec.taskId, "QUALITY", "BILIBILI_PART_QUALITY_ADJUSTED", JSONObject()
                    .put("requested_height", previous.height).put("actual_height", result.variants[selected].height))
            }
            val part = result.bilibiliParts.single { result.contentId.endsWith(":" + it.cid) }
            spec.copy(result = bilibiliPartResult(result, part), variantIndex = selected, bilibiliPending = false).also {
                if (spec.pendingRedownload == null) tasks.replaceSpec(spec.taskId, it)
            }
        }
    }
}
