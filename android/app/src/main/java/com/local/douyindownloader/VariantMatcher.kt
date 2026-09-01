package com.local.douyindownloader

import kotlin.math.abs

data class VariantMatch(
    val index: Int,
    val exact: Boolean,
)

internal fun matchVariant(previous: MediaVariant?, current: List<MediaVariant>): VariantMatch {
    if (current.isEmpty()) return VariantMatch(-1, false)
    if (previous == null) return VariantMatch(0, false)

    val exact = current.indexOfFirst {
        it.width == previous.width &&
            it.height == previous.height &&
            it.codec.equals(previous.codec, ignoreCase = true) &&
            (previous.fps <= 0 || it.fps == previous.fps)
    }
    if (exact >= 0) return VariantMatch(exact, true)
    if (previous.width <= 0 || previous.height <= 0) return VariantMatch(0, false)

    val oldPixels = previous.width.toLong() * previous.height
    val closest = current.indices.minByOrNull { index ->
        val candidate = current[index]
        val pixels = candidate.width.toLong() * candidate.height
        val resolutionDistance = abs(pixels - oldPixels)
        val codecPenalty = if (candidate.codec.equals(previous.codec, ignoreCase = true)) 0L else oldPixels
        val fpsPenalty = abs(candidate.fps - previous.fps).toLong() * 10_000L
        resolutionDistance * 10 + codecPenalty + fpsPenalty
    } ?: 0
    return VariantMatch(closest, false)
}
