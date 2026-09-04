package com.local.douyindownloader

import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

internal data class DocumentReaderData(
    val document: DocumentContent,
    val assetOutputs: Map<String, TaskOutput>,
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

internal object DocumentHtmlRenderer {
    fun render(
        document: DocumentContent,
        localAssetUrls: Map<String, String>,
    ): String {
        val assets = document.assets.associateBy(DocumentAsset::id)
        return buildString {
            append("<!doctype html><html lang=\"zh-CN\"><head>")
            append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src https: data:; media-src https:; style-src 'unsafe-inline'\">")
            append("<style>").append(STYLE).append("</style></head><body><main>")
            append("<h1>").append(escape(document.title.ifBlank { "知乎内容" })).append("</h1>")
            if (document.author.isNotBlank()) {
                append("<p class=\"meta\">作者：").append(escape(document.author)).append("</p>")
            }
            safeHttpUrl(document.sourceUrl)?.let { source ->
                append("<p class=\"meta\">来源：<a href=\"").append(attribute(source))
                    .append("\">知乎原文</a></p>")
            }
            append("<hr>")
            document.blocks.forEach { block ->
                when (block.type) {
                    DocumentBlockType.PARAGRAPH -> append("<p>").append(inline(block.text)).append("</p>")
                    DocumentBlockType.HEADING -> {
                        val level = block.level.coerceIn(1, 6)
                        append("<h$level>").append(inline(block.text)).append("</h$level>")
                    }
                    DocumentBlockType.LIST -> appendList(block.text)
                    DocumentBlockType.BLOCKQUOTE -> append("<blockquote>")
                        .append(inline(block.text)).append("</blockquote>")
                    DocumentBlockType.CODE -> append("<pre><code>")
                        .append(escape(block.text)).append("</code></pre>")
                    DocumentBlockType.HTML -> append(
                        Jsoup.clean(
                            block.text,
                            Safelist.relaxed().removeTags("img", "video", "audio", "iframe", "script", "style"),
                        ),
                    )
                    DocumentBlockType.IMAGE -> appendImage(assets[block.assetId], localAssetUrls[block.assetId])
                    DocumentBlockType.VIDEO -> appendVideo(assets[block.assetId], localAssetUrls[block.assetId])
                }
            }
            if (document.warnings.isNotEmpty()) {
                append("<section class=\"warning\"><h2>下载提示</h2><ul>")
                document.warnings.distinct().forEach { append("<li>").append(escape(it)).append("</li>") }
                append("</ul></section>")
            }
            append("</main></body></html>")
        }
    }

    private fun StringBuilder.appendList(text: String) {
        val lines = text.lines().filter(String::isNotBlank)
        val ordered = lines.firstOrNull()?.trimStart()?.matches(Regex("\\d+\\..*")) == true
        val tag = if (ordered) "ol" else "ul"
        append("<$tag>")
        lines.forEach { line ->
            val item = line.trim().replaceFirst(Regex("^(?:- |\\d+\\. )"), "")
            append("<li>").append(inline(item)).append("</li>")
        }
        append("</$tag>")
    }

    private fun StringBuilder.appendImage(asset: DocumentAsset?, localUrl: String?) {
        val remoteUrl = asset?.candidateUrls?.firstNotNullOfOrNull(::safeHttpUrl)
        val source = localUrl ?: remoteUrl ?: return
        append("<figure>")
        if (localUrl != null && asset != null) {
            append("<a href=\"app-media://open/").append(attribute(asset.id)).append("\">")
        }
        append("<img src=\"").append(attribute(source)).append("\" alt=\"")
            .append(attribute(asset?.alt.orEmpty().ifBlank { "图片" })).append("\">")
        if (localUrl != null && asset != null) append("</a>")
        if (asset?.alt?.isNotBlank() == true) append("<figcaption>").append(escape(asset.alt)).append("</figcaption>")
        if (localUrl == null) append("<figcaption class=\"warning-text\">本地文件不可用，正在尝试在线资源</figcaption>")
        append("</figure>")
    }

    private fun StringBuilder.appendVideo(asset: DocumentAsset?, localUrl: String?) {
        val remoteUrl = asset?.variants?.firstOrNull()?.urls?.firstNotNullOfOrNull(::safeHttpUrl)
        val source = localUrl ?: remoteUrl ?: return
        append("<section class=\"video\"><video controls preload=\"metadata\" src=\"")
            .append(attribute(source)).append("\"></video>")
        if (localUrl != null && asset != null) {
            append("<p><a href=\"app-media://open/").append(attribute(asset.id))
                .append("\">使用系统播放器打开</a></p>")
        } else {
            append("<p class=\"warning-text\">本地视频不可用，正在尝试在线资源</p>")
        }
        append("</section>")
    }

    private fun inline(value: String): String = escape(value)
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("~~(.+?)~~"), "<del>$1</del>")
        .replace("\n", "<br>")

    private fun safeHttpUrl(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() }
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun attribute(value: String): String = escape(value)

    private const val STYLE = """
        :root{color-scheme:light dark}body{margin:0;background:#fff;color:#202124;font-family:sans-serif;line-height:1.75}
        main{max-width:820px;margin:0 auto;padding:20px 18px 64px}h1{font-size:1.8rem;line-height:1.3}h2,h3,h4{margin-top:1.7em}
        .meta{color:#666;font-size:.92rem;margin:.25rem 0}hr{border:0;border-top:1px solid #ddd;margin:1.5rem 0}
        img,video{display:block;max-width:100%;height:auto;margin:0 auto;border-radius:10px}figure{margin:1.5rem 0}
        figcaption{text-align:center;color:#666;font-size:.88rem;margin-top:.5rem}blockquote{margin:1rem 0;padding:.6rem 1rem;border-left:4px solid #607d8b;background:#f5f5f5}
        pre{overflow:auto;padding:14px;border-radius:8px;background:#f3f3f3}a{color:#1565c0}.video{margin:1.5rem 0}.warning{margin-top:2rem;padding:1rem;background:#fff3cd;border-radius:8px}.warning-text{color:#9a6700;font-size:.88rem}
        @media(prefers-color-scheme:dark){body{background:#121212;color:#eee}.meta,figcaption{color:#aaa}hr{border-color:#444}blockquote,pre{background:#242424}a{color:#8ab4f8}.warning{background:#3d3215}.warning-text{color:#ffd666}}
    """
}
