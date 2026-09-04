package com.local.douyindownloader

internal data class DocumentReaderData(
    val document: DocumentContent,
    val assetOutputs: Map<String, TaskOutput>,
)

internal data class DocumentReaderImage(
    val asset: DocumentAsset,
    val output: TaskOutput?,
)

internal fun resolveDocumentAssetOutputs(
    document: DocumentContent,
    outputs: List<TaskOutput>,
): Map<String, TaskOutput> {
    val mediaOutputs = outputs.filter { it.relativePath.startsWith("media/") }
    val resolved = linkedMapOf<String, TaskOutput>()
    var imageIndex = 0
    var videoIndex = 0
    document.assets.forEach { asset ->
        val expectedStem = when (asset.kind) {
            DocumentAssetKind.IMAGE -> {
                imageIndex += 1
                "image_${imageIndex.toString().padStart(3, '0')}"
            }
            DocumentAssetKind.VIDEO -> {
                videoIndex += 1
                "video_${videoIndex.toString().padStart(3, '0')}"
            }
        }
        val output = mediaOutputs.firstOrNull { candidate ->
            val storedName = candidate.relativePath.substringAfterLast('/').substringBeforeLast('.')
            storedName == expectedStem
        }
        if (output != null) resolved[asset.id] = output
    }
    return resolved
}

internal fun resolveDocumentReaderImages(data: DocumentReaderData): List<DocumentReaderImage> {
    val assets = data.document.assets.associateBy(DocumentAsset::id)
    return data.document.blocks.mapNotNull { block ->
        if (block.type != DocumentBlockType.IMAGE) return@mapNotNull null
        val asset = assets[block.assetId]?.takeIf { it.kind == DocumentAssetKind.IMAGE }
            ?: return@mapNotNull null
        DocumentReaderImage(asset, data.assetOutputs[asset.id])
    }
}

internal fun documentImageStartIndex(
    images: List<DocumentReaderImage>,
    assetId: String,
): Int = images.indexOfFirst { it.asset.id == assetId }.coerceAtLeast(0)
