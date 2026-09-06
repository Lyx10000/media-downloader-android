package com.local.douyindownloader

import org.junit.Assert.*
import org.junit.Test

class BilibiliPartsTest {
    @Test fun selectedPartsPreserveIdentityAndOnlyKeepTheirOwnMedia() {
        val parts = listOf(BilibiliPartInfo("100", 1, "开头", 60), BilibiliPartInfo("200", 2, "后续", 90))
        val original = ParseResult(ok = true, platform = SourcePlatform.BILIBILI, contentId = "BV1vobn6dEAR:100",
            kind = MediaKind.VIDEO, description = "标题", bilibiliTitle = "标题", bilibiliParts = parts,
            audioUrls = listOf("https://a.bilivideo.com/audio"))
        val p1 = bilibiliPartResult(original, parts.first())
        val p2 = bilibiliPartResult(original, parts.last())
        assertEquals(original.audioUrls, p1.audioUrls)
        assertTrue(p2.audioUrls.isEmpty())
        assertEquals("BV1vobn6dEAR:200", p2.contentId)
        assertTrue(p2.canonicalUrl.endsWith("?p=2"))
        assertEquals(listOf(parts.last()), p2.bilibiliParts)
        val restored = ParseResult.fromJson(p2.toJson().toString())
        assertEquals(p2.bilibiliParts, restored.bilibiliParts)
        assertEquals("标题", restored.bilibiliTitle)
        assertEquals("BV1vobn6dEAR", bilibiliWorkId(SourcePlatform.BILIBILI, p2.contentId))
        assertEquals(p2.contentId, bilibiliWorkId(SourcePlatform.X, p2.contentId))
    }
    @Test fun oldSettingsAndSpecRemainReadable() {
        val spec = TaskSpec("test", 1, ParseResult(ok = true), 0, mode = DownloadMode.MERGE_KEEP)
        val json = org.json.JSONObject(spec.toJson()).apply { remove("bilibili_pending") }
        assertFalse(TaskSpec.fromJson(json.toString()).bilibiliPending)
        assertTrue(BatchDownloadSettings.fromJson("{}").bilibiliAllParts)
    }
}
