package com.local.douyindownloader

import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RedownloadPreflight(
    val requiredGroups: Int,
    val verifiedGroups: Int,
) {
    val allVerified: Boolean get() = verifiedGroups == requiredGroups
}

internal fun requiredRedownloadSourceGroups(
    result: ParseResult,
    variantIndex: Int,
    mode: DownloadMode,
): List<List<String>> = if (result.attachments.isNotEmpty()) {
    result.attachments.mapNotNull { attachment ->
        when (attachment.kind) {
            MediaAttachmentKind.IMAGE -> attachment.imageCandidates.takeIf(List<String>::isNotEmpty)
            MediaAttachmentKind.VIDEO,
            MediaAttachmentKind.GIF,
            -> attachment.variants.firstOrNull()?.urls?.takeIf(List<String>::isNotEmpty)
        }
    }
} else when (result.kind) {
    MediaKind.VIDEO -> buildList {
        result.variants.getOrNull(variantIndex)?.urls?.takeIf(List<String>::isNotEmpty)?.let(::add)
        if (mode != DownloadMode.VIDEO_ONLY) {
            result.audioUrls.takeIf(List<String>::isNotEmpty)?.let(::add)
        }
    }
    MediaKind.IMAGE -> buildList {
        addAll(result.imageCandidates.ifEmpty { result.imageUrls.map(::listOf) }.filter(List<String>::isNotEmpty))
        result.musicUrls.takeIf(List<String>::isNotEmpty)?.let(::add)
    }
    MediaKind.DOCUMENT -> result.document?.assets.orEmpty().mapNotNull { asset ->
        when (asset.kind) {
            DocumentAssetKind.IMAGE -> asset.candidateUrls.takeIf(List<String>::isNotEmpty)
            DocumentAssetKind.VIDEO -> asset.variants.firstOrNull()?.urls?.takeIf(List<String>::isNotEmpty)
        }
    }
}

@Singleton
internal class RedownloadSourceValidator @Inject constructor(
    private val httpClient: ParserHttpClient,
) {
    suspend fun validate(result: ParseResult, variantIndex: Int, mode: DownloadMode): RedownloadPreflight =
        withContext(Dispatchers.IO) {
            val groups = requiredRedownloadSourceGroups(result, variantIndex, mode)
            if (groups.isEmpty()) return@withContext RedownloadPreflight(0, 0)
            val executor = Executors.newFixedThreadPool(minOf(4, groups.size))
            try {
                val checks = groups.map { urls ->
                    executor.submit<Boolean> {
                        urls.take(2).any { url ->
                            runCatching {
                                if (result.platform == SourcePlatform.BILIBILI) {
                                    MediaRequestProfile.forPlatform(result.platform, result.referer)
                                        .probeContentLength(secureDownloadUrl(url))
                                } else httpClient.probeContentLength(
                                    secureDownloadUrl(url),
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to result.referer,
                                    ),
                                    timeoutSeconds = 4,
                                )
                            }.getOrDefault(-1L) > 0L
                        }
                    }
                }
                RedownloadPreflight(groups.size, checks.count { it.get() })
            } finally {
                executor.shutdownNow()
            }
        }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
