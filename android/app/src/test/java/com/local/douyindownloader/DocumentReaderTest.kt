package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun readerImagesFollowDocumentOrderAndKeepTheirOwnOutput() {
        val document = DocumentContent(
            type = DocumentType.ANSWER,
            title = "标题",
            author = "作者",
            sourceUrl = "https://www.zhihu.com/question/1/answer/2",
            blocks = listOf(
                DocumentBlock(DocumentBlockType.IMAGE, assetId = "image-2"),
                DocumentBlock(DocumentBlockType.PARAGRAPH, "正文"),
                DocumentBlock(DocumentBlockType.IMAGE, assetId = "image-1"),
            ),
            assets = listOf(
                DocumentAsset("image-1", DocumentAssetKind.IMAGE, alt = "图片"),
                DocumentAsset("image-2", DocumentAssetKind.IMAGE, alt = "第二张"),
            ),
        )
        val image1 = output("i1", "image_001.jpg", "media/image_001.jpg")
        val image2 = output("i2", "image_002.jpg", "media/image_002.jpg")
        val data = DocumentReaderData(document, mapOf("image-1" to image1, "image-2" to image2))

        val images = resolveDocumentReaderImages(data)

        assertEquals(listOf("image-2", "image-1"), images.map { it.asset.id })
        assertEquals(listOf(image2, image1), images.map { it.output })
        assertEquals(0, documentImageStartIndex(images, "image-2"))
        assertEquals(1, documentImageStartIndex(images, "image-1"))
    }

    @Test
    fun readerImageKeepsRemoteFallbackWhenLocalOutputIsMissing() {
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

        val image = resolveDocumentReaderImages(DocumentReaderData(document, emptyMap())).single()

        assertEquals(null, image.output)
        assertEquals("https://picx.zhimg.com/remote.jpg", image.asset.candidateUrls.single())
        assertEquals(0, documentImageStartIndex(listOf(image), "missing"))
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
