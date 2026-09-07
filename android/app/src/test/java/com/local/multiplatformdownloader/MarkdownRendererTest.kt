package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.model.DocumentAsset
import com.local.multiplatformdownloader.core.model.DocumentAssetKind
import com.local.multiplatformdownloader.core.model.DocumentBlock
import com.local.multiplatformdownloader.core.model.DocumentBlockType
import com.local.multiplatformdownloader.core.model.DocumentContent
import com.local.multiplatformdownloader.core.model.DocumentType
import com.local.multiplatformdownloader.feature.document.MarkdownRenderer


import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun rendersOrderedBodyLocalMediaAndWarnings() {
        val document = DocumentContent(
            type = DocumentType.ANSWER,
            title = "问题标题",
            author = "答主",
            sourceUrl = "https://www.zhihu.com/question/1/answer/2",
            blocks = listOf(
                DocumentBlock(DocumentBlockType.HEADING, "小标题", level = 2),
                DocumentBlock(DocumentBlockType.PARAGRAPH, "第一段"),
                DocumentBlock(DocumentBlockType.IMAGE, assetId = "image-1"),
                DocumentBlock(DocumentBlockType.VIDEO, assetId = "video-1"),
            ),
            assets = listOf(
                DocumentAsset("image-1", DocumentAssetKind.IMAGE, candidateUrls = listOf("https://pic.zhimg.com/a.jpg"), alt = "图注"),
                DocumentAsset("video-1", DocumentAssetKind.VIDEO, candidateUrls = listOf("https://video.example/fallback.mp4"), alt = "视频"),
            ),
        )

        val markdown = MarkdownRenderer.render(
            document,
            localPaths = mapOf("image-1" to "media/image_1.jpg"),
            failures = mapOf("video-1" to "HTTP 403"),
        )

        assertTrue(markdown.contains("# 问题标题"))
        assertTrue(markdown.contains("作者：答主"))
        assertTrue(markdown.indexOf("## 小标题") < markdown.indexOf("![图注](media/image_1.jpg)"))
        assertTrue(markdown.contains("[视频](https://video.example/fallback.mp4)"))
        assertTrue(markdown.contains("## 下载警告"))
        assertTrue(markdown.contains("video-1：HTTP 403"))
        assertFalse(markdown.contains("javascript:"))
    }

    @Test
    fun rendersLocalVideoAsClickableHtmlPlayer() {
        val document = DocumentContent(
            type = DocumentType.ARTICLE,
            title = "文章",
            author = "作者",
            sourceUrl = "https://zhuanlan.zhihu.com/p/1",
            blocks = listOf(DocumentBlock(DocumentBlockType.VIDEO, assetId = "video-1")),
            assets = listOf(DocumentAsset("video-1", DocumentAssetKind.VIDEO, alt = "内嵌视频")),
        )

        val markdown = MarkdownRenderer.render(
            document,
            localPaths = mapOf("video-1" to "media/video_1.mp4"),
        )

        assertTrue(markdown.contains("[内嵌视频](media/video_1.mp4)"))
        assertTrue(markdown.contains("<video controls src=\"media/video_1.mp4\"></video>"))
    }
}
