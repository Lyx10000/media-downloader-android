package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentReaderTest {
    @Test
    fun mapsDocumentAssetsToNestedTaskOutputs() {
        val document = document(
            assets = listOf(
                DocumentAsset("image-1", DocumentAssetKind.IMAGE),
                DocumentAsset("video-1", DocumentAssetKind.VIDEO),
                DocumentAsset("image-2", DocumentAssetKind.IMAGE),
            ),
        )
        val image1 = output("i1", "image_001.jpg", "media/image_001.jpg")
        val video1 = output("v1", "video_001.mp4", "media/video_001.mp4")
        val cover = output("c1", "video_001_cover.jpg", "media/video_001_cover.jpg")
        val image2 = output("i2", "renamed.webp", "media/image_002.webp")

        val resolved = resolveDocumentAssetOutputs(document, listOf(image1, video1, cover, image2))

        assertEquals(image1, resolved["image-1"])
        assertEquals(video1, resolved["video-1"])
        assertEquals(image2, resolved["image-2"])
        assertFalse(resolved.containsValue(cover))
    }

    @Test
    fun htmlUsesInternalMediaUrlsAndEscapesUntrustedText() {
        val document = DocumentContent(
            type = DocumentType.ANSWER,
            title = "标题 <script>alert(1)</script>",
            author = "作者",
            sourceUrl = "https://www.zhihu.com/question/1/answer/2",
            blocks = listOf(
                DocumentBlock(DocumentBlockType.PARAGRAPH, "正文 <img src=x onerror=alert(1)>"),
                DocumentBlock(DocumentBlockType.IMAGE, assetId = "image-1"),
                DocumentBlock(DocumentBlockType.VIDEO, assetId = "video-1"),
            ),
            assets = listOf(
                DocumentAsset("image-1", DocumentAssetKind.IMAGE, alt = "图片"),
                DocumentAsset("video-1", DocumentAssetKind.VIDEO, alt = "视频"),
            ),
        )

        val html = DocumentHtmlRenderer.render(
            document,
            mapOf(
                "image-1" to "https://appassets.androidplatform.net/document-media/image-1",
                "video-1" to "https://appassets.androidplatform.net/document-media/video-1",
            ),
        )

        assertTrue(html.contains("document-media/image-1"))
        assertTrue(html.contains("document-media/video-1"))
        assertTrue(html.contains("app-media://open/video-1"))
        assertFalse(html.contains("loading=\"lazy\""))
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertFalse(html.contains("<img src=x onerror=alert(1)>"))
    }

    @Test
    fun htmlFallsBackToRemoteImageWhenLocalOutputIsMissing() {
        val document = document(
            blocks = listOf(DocumentBlock(DocumentBlockType.IMAGE, assetId = "image-1")),
            assets = listOf(
                DocumentAsset(
                    "image-1",
                    DocumentAssetKind.IMAGE,
                    candidateUrls = listOf("https://picx.zhimg.com/remote.jpg"),
                ),
            ),
        )

        val html = DocumentHtmlRenderer.render(document, emptyMap())

        assertTrue(html.contains("https://picx.zhimg.com/remote.jpg"))
        assertTrue(html.contains("本地文件不可用，正在尝试在线资源"))
    }

    private fun document(
        blocks: List<DocumentBlock> = emptyList(),
        assets: List<DocumentAsset>,
    ) = DocumentContent(
        type = DocumentType.ARTICLE,
        title = "文章",
        author = "作者",
        sourceUrl = "https://zhuanlan.zhihu.com/p/1",
        blocks = blocks,
        assets = assets,
    )

    private fun output(uri: String, name: String, relativePath: String) = TaskOutput(
        uri = uri,
        displayName = name,
        mimeType = mediaMimeType(name),
        relativePath = relativePath,
    )
}
