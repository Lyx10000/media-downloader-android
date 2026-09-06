package com.local.douyindownloader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InstagramCreatorFeedProfileTest {
    private fun item(handle: String = "author", id: String = "42", wrapped: Boolean = false, ad: Boolean = false): JSONObject {
        val media = JSONObject().put("code", "ABC123").put("media_type", 2)
            .put("user", JSONObject().put("username", handle).put("pk", id).put("id", "178414000"))
        if (ad) media.put("is_ad", true)
        return JSONObject().put("node", if (wrapped) JSONObject().put("media", media) else media)
    }

    private fun connection(vararg items: JSONObject) = JSONObject().put("edges", JSONArray(items.toList()))
        .put("page_info", JSONObject().put("has_next_page", true).put("end_cursor", "next-page"))

    @Test fun `username and stable owner in feed suffice without cosmetic profile fields`() {
        val root = connection(item())
        val profile = InstagramCreatorFeedProfile.fromConnection(root, "AUTHOR")
        assertEquals("42", profile.stableId)
        assertEquals("author", profile.nickname)
        assertTrue(profile.metrics.isEmpty())
        val page = InstagramCreatorNormalizer.page(root, profile, InstagramCreatorCursor(), "", 1)
        assertEquals("ABC123", page.works.single().contentId)
        assertEquals("next-page", InstagramCreatorCursor.parse(page.nextCursor).after)
    }

    @Test fun `recommendations viewer and ads cannot supply target identity`() {
        val root = connection(item("viewer", "1"), item("author", "42", ad = true))
        assertNull(InstagramCreatorFeedProfile.findOwner(root, "author"))
        assertThrows(CreatorSourceException::class.java) { InstagramCreatorFeedProfile.fromConnection(root, "author") }
    }

    @Test fun `Reel wrapper supports exact identity and uses pk`() {
        assertEquals("42", InstagramCreatorFeedProfile.fromConnection(connection(item(wrapped = true)), "author").stableId)
    }

    @Test fun `conflicting owners refuse association`() {
        val error = assertThrows(CreatorSourceException::class.java) {
            InstagramCreatorFeedProfile.findOwner(connection(item(), item(id = "43")), "author")
        }
        assertEquals("AUTHOR_CHANGED", error.code)
    }

    @Test fun `empty or changed feed never means author deleted`() {
        assertEquals("CREATOR_IDENTITY_UNAVAILABLE", assertThrows(CreatorSourceException::class.java) {
            InstagramCreatorFeedProfile.fromConnection(connection(), "author")
        }.code)
        assertEquals("RESPONSE_CHANGED", assertThrows(CreatorSourceException::class.java) {
            InstagramCreatorFeedProfile.fromConnection(JSONObject(), "author")
        }.code)
    }
}
