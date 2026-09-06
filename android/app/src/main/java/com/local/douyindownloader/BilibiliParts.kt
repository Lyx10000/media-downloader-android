package com.local.douyindownloader

import org.json.JSONObject

data class BilibiliPartInfo(val cid: String, val page: Int, val title: String, val durationSeconds: Long) {
    fun toJson() = JSONObject().put("cid", cid).put("page", page).put("title", title).put("duration", durationSeconds)
    companion object {
        fun fromJson(value: JSONObject): BilibiliPartInfo? = BilibiliPartInfo(value.optString("cid"),
            value.optInt("page"), value.optString("title"), value.optLong("duration").coerceAtLeast(0))
            .takeIf { it.cid.matches(Regex("[1-9][0-9]*")) && it.page > 0 }
    }
}

internal fun bilibiliWorkId(platform: SourcePlatform, contentId: String): String =
    if (platform == SourcePlatform.BILIBILI) contentId.substringBefore(':') else contentId

internal fun bilibiliPartResult(result: ParseResult, part: BilibiliPartInfo): ParseResult {
    require(result.platform == SourcePlatform.BILIBILI && part in result.bilibiliParts)
    val bv = result.contentId.substringBefore(':')
    require(bv.matches(Regex("BV[A-Za-z0-9]{10}")))
    val same = result.contentId == "$bv:${part.cid}"
    return result.copy(contentId = "$bv:${part.cid}", canonicalUrl = "https://www.bilibili.com/video/$bv?p=${part.page}",
        description = "${result.bilibiliTitle.ifBlank { result.description }} · P${part.page} ${part.title}",
        variants = if (same) result.variants else result.variants.map { it.copy(urls = emptyList()) },
        audioUrls = if (same) result.audioUrls else emptyList(), bilibiliParts = listOf(part), rawJson = "{}")
}

internal fun bilibiliGroupKey(task: TaskRecord): String? = task.contentId.substringBefore(':')
    .takeIf { task.platform == SourcePlatform.BILIBILI && it.matches(Regex("BV[A-Za-z0-9]{10}")) }

internal fun representativeCreatorTask(records: List<TaskRecord>): TaskRecord? =
    records.firstOrNull { it.status == TaskStatus.RUNNING }
        ?: records.firstOrNull { it.status == TaskStatus.QUEUED }
        ?: records.firstOrNull { it.status in setOf(TaskStatus.FAILED, TaskStatus.CANCELLED) }
        ?: records.firstOrNull { it.fileState != FileState.AVAILABLE }
        ?: records.maxByOrNull(TaskRecord::createdAt)
