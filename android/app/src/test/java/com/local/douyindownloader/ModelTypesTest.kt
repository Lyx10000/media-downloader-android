package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTypesTest {
    @Test
    fun persistedValuesRemainBackwardCompatible() {
        assertEquals("merge_keep", DownloadMode.MERGE_KEEP.wireValue)
        assertEquals("tracks", DownloadMode.TRACKS.wireValue)
        assertEquals("video_only", DownloadMode.VIDEO_ONLY.wireValue)
        assertEquals("audio_only", DownloadMode.AUDIO_ONLY.wireValue)
        assertEquals("SAF", StorageMode.SAF.wireValue)
        assertEquals("AVAILABLE", FileState.AVAILABLE.wireValue)
        assertEquals("COMPLETE", TaskStatus.COMPLETE.wireValue)
        assertEquals("image", MediaKind.IMAGE.wireValue)
        assertEquals("document", MediaKind.DOCUMENT.wireValue)
        assertEquals("douyin", SourcePlatform.DOUYIN.wireValue)
        assertEquals("xiaohongshu", SourcePlatform.XIAOHONGSHU.wireValue)
        assertEquals("zhihu", SourcePlatform.ZHIHU.wireValue)
    }

    @Test
    fun legacyAndUnknownValuesUseSafeMappings() {
        assertEquals(DownloadMode.MERGE_KEEP, DownloadMode.fromWire("unexpected"))
        assertEquals(StorageMode.LEGACY, StorageMode.fromWire("unexpected"))
        assertEquals(FileState.UNKNOWN, FileState.fromWire("unexpected"))
        assertEquals(MediaKind.VIDEO, MediaKind.fromWire("unexpected"))
        assertEquals(SourcePlatform.DOUYIN, SourcePlatform.fromWire("unexpected"))
        assertEquals("FUTURE_STATUS", TaskStatus.fromWire("FUTURE_STATUS").wireValue)
    }

    @Test
    fun documentResultRoundTripsWithoutChangingLargeStringIds() {
        val document = DocumentContent(
            type = DocumentType.ANSWER,
            title = "问题标题",
            author = "作者",
            sourceUrl = "https://www.zhihu.com/question/1/answer/2079127079271011205",
            blocks = listOf(
                DocumentBlock(DocumentBlockType.PARAGRAPH, text = "正文 **加粗**"),
                DocumentBlock(DocumentBlockType.IMAGE, assetId = "image-1"),
                DocumentBlock(DocumentBlockType.VIDEO, assetId = "2079000000000000001"),
            ),
            assets = listOf(
                DocumentAsset(
                    id = "image-1",
                    kind = DocumentAssetKind.IMAGE,
                    candidateUrls = listOf("https://picx.zhimg.com/example.jpg"),
                    alt = "说明",
                ),
                DocumentAsset(
                    id = "2079000000000000001",
                    kind = DocumentAssetKind.VIDEO,
                    variants = listOf(
                        MediaVariant(1920, 1080, 2_000_000, 30, "H.264", 10, "api", listOf("https://vdn.vzuu.com/video.mp4")),
                    ),
                ),
            ),
        )
        val result = ParseResult(
            ok = true,
            platform = SourcePlatform.ZHIHU,
            contentId = "2079127079271011205",
            kind = MediaKind.DOCUMENT,
            author = "作者",
            authorAccountId = "public-account",
            document = document,
        )

        val restored = ParseResult.fromJson(result.toJson().toString())

        assertEquals("2079127079271011205", restored.contentId)
        assertEquals("作者", restored.author)
        assertEquals("public-account", restored.authorAccountId)
        assertEquals(document, restored.document)
    }

    @Test
    fun taskOutputKeepsNestedRelativePath() {
        val output = TaskOutput(
            uri = "content://downloads/1",
            displayName = "image_1.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 42,
            relativePath = "media/image_1.jpg",
        )

        assertEquals(output, TaskOutput.fromJson(output.toJson()))
    }

}
