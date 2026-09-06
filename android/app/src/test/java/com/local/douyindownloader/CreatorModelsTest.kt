package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorModelsTest {
    @Test
    fun `creator keys never merge platforms`() {
        assertEquals("douyin:same", creatorKey(SourcePlatform.DOUYIN, "same"))
        assertEquals("xiaohongshu:same", creatorKey(SourcePlatform.XIAOHONGSHU, "same"))
    }

    @Test
    fun `creator platform filter only affects author entries`() {
        val douyin = profile("douyin", SourcePlatform.DOUYIN)
        val xhs = profile("xhs", SourcePlatform.XIAOHONGSHU)
        val zhihu = profile("zhihu", SourcePlatform.ZHIHU)

        assertEquals(listOf(douyin, zhihu), filterCreatorsByPlatform(listOf(douyin, xhs, zhihu), null))
        assertEquals(emptyList<CreatorProfile>(), filterCreatorsByPlatform(listOf(douyin, xhs, zhihu), SourcePlatform.XIAOHONGSHU))
    }

    @Test
    fun `remote check failures remain visible on creator page`() {
        val failed = work("failed").copy(
            pageNumber = 1,
            remoteStatus = CreatorWorkRemoteStatus.CHECK_FAILED,
        )
        val unavailable = work("unavailable").copy(
            pageNumber = 1,
            remoteStatus = CreatorWorkRemoteStatus.UNAVAILABLE,
        )

        assertEquals(listOf(failed, unavailable), visibleCreatorPage(listOf(failed, unavailable), 1))
    }

    @Test
    fun `select current page skips intact local downloads`() {
        val downloaded = work(
            id = "downloaded",
            task = TaskRecord(
                id = "task",
                createdAt = 1,
                status = TaskStatus.COMPLETE,
                stage = "已完成",
                progress = 100,
                title = "downloaded",
                outputs = listOf(TaskOutput("content://file")),
                error = "",
                fileState = FileState.AVAILABLE,
            ),
        )
        val missing = work(
            id = "missing",
            task = downloaded.task?.copy(id = "missing-task", fileState = FileState.MISSING),
        )
        val fresh = work("fresh")

        val selected = selectCurrentCreatorPage(emptySet(), listOf(downloaded, missing, fresh))

        assertFalse(downloaded.key in selected)
        assertTrue(missing.key in selected)
        assertTrue(fresh.key in selected)
    }

    @Test
    fun `selecting another page preserves the previous page selection`() {
        val selected = selectCurrentCreatorPage(setOf("douyin:older"), listOf(work("newer")))

        assertEquals(setOf("douyin:older", "douyin:newer"), selected)
    }

    @Test
    fun `quality cap chooses best variant under target`() {
        val variants = listOf(
            variant(2160, "H.265"),
            variant(1080, "H.265"),
            variant(1080, "H.264"),
            variant(720, "H.264"),
        )

        assertEquals(2, chooseBatchVariant(variants, BatchVideoQuality.UP_TO_1080P, true))
        assertEquals(3, chooseBatchVariant(variants, BatchVideoQuality.UP_TO_720P, false))
        assertEquals(0, chooseBatchVariant(variants, BatchVideoQuality.HIGHEST, false))
    }

    @Test
    fun `unknown sizes produce a range instead of fake precision`() {
        val estimate = estimateBatchSize(
            listOf(work("video").copy(kind = MediaKind.VIDEO, durationMs = 60_000L)),
            BatchVideoQuality.UP_TO_1080P,
        )

        assertTrue(estimate.minimumBytes > 0L)
        assertTrue(estimate.maximumBytes > estimate.minimumBytes)
        assertEquals(0, estimate.knownItems)
        assertEquals(1, estimate.estimatedItems)
    }

    @Test
    fun `creator folder is stable and removes unsafe separators`() {
        val profile = CreatorProfile(
            key = "douyin:stable",
            platform = SourcePlatform.DOUYIN,
            stableId = "stable12345678",
            accountId = "account/one",
            profileUrl = "https://www.douyin.com/user/stable",
            nickname = "name:bad",
        )
        val folder = creatorWorkFolder(profile, work("123"), 1_700_000_000_000L)

        assertTrue(folder.startsWith("抖音/name_bad_account_one_12345678/"))
        assertFalse(':' in folder)
    }

    @Test
    fun `creator redownload stays under author directory`() {
        val folder = redownloadTaskFolderName("抖音/author_id/old_work", 1L, "task")
        assertTrue(folder.startsWith("抖音/author_id/"))
        assertTrue(folder.endsWith("_task_r001"))
    }

    @Test
    fun `persisted creator directory does not change with profile rename`() {
        val original = CreatorProfile(
            key = "douyin:stable",
            platform = SourcePlatform.DOUYIN,
            stableId = "stable12345678",
            accountId = "account",
            profileUrl = "https://www.douyin.com/user/stable",
            directoryName = "first_name_account_12345678",
            nickname = "renamed",
        )

        assertTrue(creatorWorkFolder(original, work("123"), 1L).contains("/first_name_account_12345678/"))
    }

    @Test
    fun `creator metadata survives parse result persistence`() {
        val value = ParseResult(
            ok = true,
            platform = SourcePlatform.XIAOHONGSHU,
            contentId = "note",
            author = "author",
            authorAccountId = "red-id",
            authorStableId = "stable-id",
            authorProfileUrl = "https://www.xiaohongshu.com/user/profile/stable-id",
            authorAvatarUrl = "https://example.com/avatar.webp",
        )

        val restored = ParseResult.fromJson(value.toJson().toString())

        assertEquals(value.authorStableId, restored.authorStableId)
        assertEquals(value.authorProfileUrl, restored.authorProfileUrl)
        assertEquals(value.authorAvatarUrl, restored.authorAvatarUrl)
    }

    @Test
    fun `remote loss never hides an intact local copy`() {
        val localTask = TaskRecord(
            id = "task",
            createdAt = 1L,
            status = TaskStatus.COMPLETE,
            stage = "已完成",
            progress = 100,
            title = "title",
            outputs = listOf(TaskOutput("content://file")),
            error = "",
            fileState = FileState.AVAILABLE,
        )
        val unavailable = work("gone", localTask).copy(
            remoteStatus = CreatorWorkRemoteStatus.UNAVAILABLE,
        )

        assertEquals(CreatorWorkLocalStatus.AVAILABLE, unavailable.localStatus)
        assertEquals(CreatorWorkRemoteStatus.UNAVAILABLE, unavailable.remoteStatus)
    }

    @Test
    fun `web fallback is limited to supported platform and media combinations`() {
        assertTrue(
            shouldUseCreatorWebFallback(
                SourcePlatform.XIAOHONGSHU,
                MediaKind.IMAGE,
                "DETAIL_EMPTY",
            ),
        )
        assertTrue(
            shouldUseCreatorWebFallback(
                SourcePlatform.XIAOHONGSHU,
                MediaKind.VIDEO,
                "URL_RESOLVE_FAILED",
            ),
        )
        assertTrue(
            shouldUseCreatorWebFallback(
                SourcePlatform.ZHIHU,
                MediaKind.DOCUMENT,
                "AUTH_OR_RISK",
            ),
        )
        assertTrue(
            shouldUseCreatorWebFallback(
                SourcePlatform.ZHIHU,
                MediaKind.DOCUMENT,
                "DETAIL_EMPTY",
            ),
        )
        assertFalse(
            shouldUseCreatorWebFallback(
                SourcePlatform.ZHIHU,
                MediaKind.VIDEO,
                "AUTH_OR_RISK",
            ),
        )
        assertFalse(
            shouldUseCreatorWebFallback(
                SourcePlatform.XIAOHONGSHU,
                MediaKind.IMAGE,
                "CONTENT_UNAVAILABLE",
            ),
        )
        assertFalse(
            shouldUseCreatorWebFallback(
                SourcePlatform.DOUYIN,
                MediaKind.VIDEO,
                "DETAIL_EMPTY",
            ),
        )
    }

    @Test
    fun `web preparation remains ahead of ordinary risk-paused entries`() {
        val entries = listOf(
            batchWork("web", CreatorBatchWorkStatus.WEB_REQUIRED),
            batchWork("risk", CreatorBatchWorkStatus.PAUSED),
        )

        assertEquals(CreatorBatchStatus.WAITING_FOREGROUND, batchStatusAfterPreparation(entries))
        assertEquals(
            CreatorBatchStatus.PAUSED,
            batchStatusAfterPreparation(listOf(batchWork("risk", CreatorBatchWorkStatus.PAUSED))),
        )
    }

    @Test
    fun `batch web request reports persisted progress and security context`() {
        val preparation = CreatorBatchPreparation(
            batchId = "batch",
            creatorKey = "xiaohongshu:creator",
            status = CreatorBatchStatus.WAITING_FOREGROUND,
            total = 14,
            processed = 3,
            pendingWorkKey = "xiaohongshu:note",
            pendingUrl = "https://www.xiaohongshu.com/explore/note?xsec_token=secret&xsec_source=pc_user",
            pendingStatus = CreatorBatchWorkStatus.WEB_REQUIRED,
            attemptCount = 1,
        )

        assertEquals(4, preparation.webRequest?.position)
        assertEquals(1, preparation.webRequest?.attemptCount)
        assertTrue(hasXsecToken(preparation.pendingUrl))
        assertFalse(hasXsecToken("https://www.xiaohongshu.com/explore/note"))
    }

    private fun work(id: String, task: TaskRecord? = null) = CreatorWork(
        key = "douyin:$id",
        creatorKey = "douyin:creator",
        platform = SourcePlatform.DOUYIN,
        contentId = id,
        canonicalUrl = "https://www.douyin.com/video/$id",
        kind = MediaKind.VIDEO,
        title = id,
        task = task,
    )

    private fun profile(id: String, platform: SourcePlatform) = CreatorProfile(
        key = creatorKey(platform, id),
        platform = platform,
        stableId = id,
        accountId = id,
        profileUrl = "https://example.com/$id",
        nickname = id,
    )

    private fun variant(height: Int, codec: String) = MediaVariant(
        width = height * 16 / 9,
        height = height,
        bitrate = 1_000_000,
        fps = 30,
        codec = codec,
        size = 1,
        sizeSource = "api",
        urls = listOf("https://example.com/$height/$codec"),
    )

    private fun batchWork(id: String, status: String) = BatchWorkEntity(
        batchId = "batch",
        workKey = id,
        status = status,
        taskId = "",
        error = "",
    )
}
