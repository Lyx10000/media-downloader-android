package com.local.douyindownloader

import androidx.work.ExistingWorkPolicy
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class TaskRedownloadCoordinator @Inject constructor(
    private val repository: DownloadTaskRepository,
    private val parser: ParserGateway,
    private val inspector: StorageInspector,
    private val deletionCoordinator: TaskDeletionCoordinator,
    private val scheduler: DownloadScheduler,
    private val logger: DiagnosticLogger,
) {
    suspend fun retry(
        task: TaskRecord,
        cookieHeader: String,
        customTreeUri: String?,
    ): String {
        val originalSpec = repository.getSpec(task.id)
            ?: return "旧任务缺少作品 ID，无法自动重新解析"
        if (originalSpec.result.awemeId.isBlank()) {
            return "旧任务缺少作品 ID，无法自动重新解析"
        }
        val resolvedStorage = resolveStorage(originalSpec, customTreeUri)
            ?: return "保存目录已失效，请重新选择目录后再下载"

        repository.update(task.id, TaskStatus.RUNNING, "正在重新解析作品", 0)
        logger.event(task.id, "REDOWNLOAD", "REPARSE_STARTED", JSONObject().apply {
            put("aweme_id", originalSpec.result.awemeId)
            put("kind", originalSpec.result.kind.wireValue)
        })
        val refreshed = parser.parse(originalSpec.stableSource(), cookieHeader)
        if (!refreshed.ok) {
            val hint = if (refreshed.errorCode == "AUTH_OR_RISK") {
                "请到设置中登录或刷新抖音环境后重试"
            } else {
                refreshed.message
            }
            repository.update(task.id, TaskStatus.FAILED, "重新解析失败", 0, hint)
            logger.event(task.id, "REDOWNLOAD", "REPARSE_FAILED", JSONObject().apply {
                put("code", refreshed.errorCode)
                put("message", refreshed.message)
            })
            return hint
        }

        val previous = originalSpec.result.variants.getOrNull(originalSpec.variantIndex)
        val match = if (refreshed.kind == MediaKind.IMAGE) VariantMatch(0, true)
        else matchVariant(previous, refreshed.variants)
        if (refreshed.kind != MediaKind.IMAGE && match.index < 0) {
            repository.update(
                task.id,
                TaskStatus.FAILED,
                "重新解析失败",
                0,
                "当前没有可下载的视频档位",
            )
            return "当前没有可下载的视频档位"
        }

        val cleanup = deletionCoordinator.deleteOutputsForRedownload(task.id)
        if (!cleanup.success) return cleanup.message

        val now = System.currentTimeMillis()
        val updatedSpec = originalSpec.copy(
            result = refreshed,
            variantIndex = match.index.coerceAtLeast(0),
            sourceText = originalSpec.stableSource(),
            storageMode = resolvedStorage.first,
            storageRoot = resolvedStorage.second,
            taskFolder = taskFolderName(now, task.id),
        )
        repository.replaceSpec(task.id, updatedSpec)
        repository.update(task.id, TaskStatus.QUEUED, "等待重新下载", 0)
        logger.event(task.id, "REDOWNLOAD", "REPARSE_COMPLETE", JSONObject().apply {
            put("variant", match.index)
            put("exact_match", match.exact)
            put("images", refreshed.imageCandidates.size)
        })
        scheduler.enqueue(task.id, ExistingWorkPolicy.REPLACE)
        return if (refreshed.kind != MediaKind.IMAGE && !match.exact) {
            if (previous == null) "已选择当前可获得的最高档位"
            else "原清晰度已不可用，已选择当前最接近的档位"
        } else {
            "已重新解析并开始下载"
        }
    }

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
}
