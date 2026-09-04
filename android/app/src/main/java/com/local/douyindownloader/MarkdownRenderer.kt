package com.local.douyindownloader

internal object MarkdownRenderer {
    fun render(
        document: DocumentContent,
        localPaths: Map<String, String>,
        failures: Map<String, String> = emptyMap(),
    ): String {
        val assets = document.assets.associateBy(DocumentAsset::id)
        return buildString {
            append("# ").append(singleLine(document.title.ifBlank { "知乎内容" })).append("\n\n")
            if (document.author.isNotBlank()) append("作者：").append(singleLine(document.author)).append("\n\n")
            append("来源：[").append(document.sourceUrl).append("](")
                .append(document.sourceUrl).append(")\n\n")
            append("---\n\n")
            document.blocks.forEach { block ->
                when (block.type) {
                    DocumentBlockType.PARAGRAPH -> append(block.text)
                    DocumentBlockType.HEADING -> append("#".repeat(block.level.coerceIn(1, 6)))
                        .append(' ').append(block.text)
                    DocumentBlockType.LIST -> append(block.text)
                    DocumentBlockType.BLOCKQUOTE -> append(
                        block.text.lines().joinToString("\n") { line -> "> $line" },
                    )
                    DocumentBlockType.CODE -> {
                        append("```").append(block.language).append('\n')
                        append(block.text).append("\n```")
                    }
                    DocumentBlockType.HTML -> append(block.text)
                    DocumentBlockType.IMAGE -> {
                        val asset = assets[block.assetId]
                        val path = localPaths[block.assetId] ?: asset?.candidateUrls?.firstOrNull().orEmpty()
                        if (path.isNotBlank()) {
                            append("![").append(label(asset?.alt.orEmpty().ifBlank { "图片" }))
                                .append("](").append(path).append(')')
                        }
                    }
                    DocumentBlockType.VIDEO -> {
                        val asset = assets[block.assetId]
                        val local = localPaths[block.assetId]
                        val remote = asset?.variants?.firstOrNull()?.urls?.firstOrNull()
                            ?: asset?.candidateUrls?.firstOrNull()
                            ?: asset?.coverUrls?.firstOrNull()
                        val path = local ?: remote.orEmpty()
                        if (path.isNotBlank()) {
                            val mediaLabel = label(asset?.alt.orEmpty().ifBlank { "视频" })
                            append('[').append(mediaLabel).append("](").append(path).append(')')
                            if (local != null) {
                                append("\n\n<video controls src=\"").append(local)
                                    .append("\"></video>")
                            }
                        }
                    }
                }
                append("\n\n")
            }
            val allWarnings = document.warnings + failures.map { (id, reason) -> "$id：$reason" }
            if (allWarnings.isNotEmpty()) {
                append("---\n\n## 下载警告\n\n")
                allWarnings.distinct().forEach { append("- ").append(it).append('\n') }
            }
        }.trimEnd() + "\n"
    }

    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n]+"), " ").trim()

    private fun label(value: String): String = singleLine(value).replace("[", "\\[").replace("]", "\\]")
}
