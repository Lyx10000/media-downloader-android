package com.local.douyindownloader

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class InstagramCreatorPageDataTest {
    @Test fun `diagnostics distinguish identity conflict without exposing values`() {
        val first = user("private_author", "99112233")
        val second = user("private_author", "88445566")
        var fields = JSONObject()
        assertNull(InstagramCreatorPageData.find(JSONArray().put(first).put(second), "private_author") { fields = it })
        assertEquals("identity_conflict", fields.getString("outcome"))
        assertEquals(2, fields.getInt("distinct_identity_count"))
        assertEquals(2, fields.getInt("differing_id_fields_count"))
        listOf("private_author", "99112233", "88445566", "17841400000000", "简介").forEach {
            assertFalse(fields.toString().contains(it))
        }
    }

    @Test fun `snapshot diagnostics whitelist metadata and still report missing target`() {
        var fields = JSONObject()
        val snapshot = WebPageSnapshot("https://www.instagram.com/author/", "{}",
            captureDiagnostics = """{"ready_state":"complete","parsed_script_count":2,"cookie":"secret","body":"private"}""")
        assertThrows(CreatorSourceException::class.java) {
            InstagramCreatorPageData.fromSnapshot(snapshot, "author") { fields = it }
        }
        assertEquals("target_missing", fields.getString("outcome"))
        assertEquals(2, fields.getInt("parsed_script_count"))
        assertTrue(fields.getBoolean("route_matches"))
        assertFalse(fields.toString().contains("secret"))
        assertFalse(fields.toString().contains("private"))
        assertNotNull(InstagramCreatorPageData.find(user(), "author") { error("observer failed") })
    }

    @Test fun `oversized encoded data reports char limit without changing rejection`() {
        var fields = JSONObject()
        assertNull(InstagramCreatorPageData.find("[" + " ".repeat(4_000_000) + "]", "author") { fields = it })
        assertTrue(fields.getBoolean("char_limit_hit"))
        assertEquals(0, fields.getInt("decoded_strings"))
    }

    private fun user(handle: String = "author", pk: String = "42") = JSONObject()
        .put("username", handle).put("pk", pk).put("id", "17841400000000")
        .put("full_name", "作者").put("follower_count", 34_348).put("following_count", 126)
        .put("biography", "简介").put("is_private", false)

    @Test fun `encoded mobile page state yields exact author and uses pk not graph id`() {
        val state = JSONObject().put("states", JSONArray().put(JSONObject().put("payload",
            JSONObject().put("data", JSONObject().put("user", user())).toString())))
        val result = InstagramCreatorPageData.find(state.toString(), "AUTHOR")!!
        val profile = InstagramCreatorNormalizer.profile(result, "author")
        assertEquals("42", profile.stableId)
        assertEquals("34348", profile.metrics.first().value)
    }

    @Test fun `richer profile wins over route params recommendation and repeated avatar stub`() {
        val state = JSONArray().put(JSONObject().put("username", "author"))
            .put(user("viewer", "99"))
            .put(user().apply { remove("biography"); remove("follower_count"); remove("following_count") })
            .put(user())
        assertEquals("简介", InstagramCreatorPageData.find(state, "author")!!.getString("biography"))
        assertNull(InstagramCreatorPageData.find(state, "someone_else"))
    }

    @Test fun `ambiguous identity and shell page are not successful author results`() {
        assertNull(InstagramCreatorPageData.find(JSONArray().put(user()).put(user(pk = "43")), "author"))
        assertNull(InstagramCreatorPageData.fromHtml("<html><title>Instagram</title><body></body></html>", "author"))
        assertNull(InstagramCreatorPageData.find(JSONObject().put("username", "author").put("id", "42"), "author"))
    }

    @Test fun `html state and snapshot support same nested encoding but reject redirected identity`() {
        val encoded = JSONObject().put("payload", user().toString()).toString()
        val html = "<script type=\"application/json\">$encoded</script>"
        assertEquals("42", InstagramCreatorPageData.fromHtml(html, "author")!!.getString("pk"))
        val snapshot = WebPageSnapshot("https://www.instagram.com/author/", encoded)
        assertEquals("42", InstagramCreatorPageData.fromSnapshot(snapshot, "author").getString("pk"))
        listOf("https://www.instagram.com/viewer/", "https://www.instagram.com/accounts/login/",
            "https://instagram.com.evil.test/author/").forEach { url ->
            assertThrows(CreatorSourceException::class.java) { InstagramCreatorPageData.fromSnapshot(snapshot.copy(finalUrl = url), "author") }
        }
        assertThrows(CreatorSourceException::class.java) { InstagramCreatorPageData.fromSnapshot(snapshot.copy(initialData = "{}"), "author") }
    }

    @Test fun `profile page diagnostics do not mislabel webpage as JSON API`() {
        val fields = InstagramCreatorDiagnosticFields.request("profile_web", "", mapOf("User-Agent" to SOCIAL_CREATOR_UA), false)
        assertEquals("GET", fields.getString("method"))
        assertEquals("/{username}/", fields.getString("endpoint"))
        assertFalse(fields.getBoolean("ig_app_header_present"))
    }

    @Test fun `media ownership uses same pk identity when graph id is also present`() {
        val profile = InstagramCreatorNormalizer.profile(user(), "author")
        val connection = JSONObject().put("edges", JSONArray().put(JSONObject().put("node",
            JSONObject().put("code", "ABC").put("media_type", 1).put("user", user()))))
            .put("page_info", JSONObject().put("has_next_page", false))
        assertEquals(1, InstagramCreatorNormalizer.page(connection, profile, InstagramCreatorCursor(), "", 1).works.size)
    }

    @Test fun `optional real browser public profile fixture`() {
        val path = System.getenv("INSTAGRAM_CREATOR_PUBLIC_FIXTURE").orEmpty()
        assumeTrue(path.isNotBlank())
        val fixture = JSONObject(File(path).readText())
        val result = InstagramCreatorPageData.find(fixture.getJSONArray("found"), "nanjibeivv")!!
        val profile = InstagramCreatorNormalizer.profile(result, "nanjibeivv")
        assertEquals("77207706886", profile.stableId)
        assertEquals("南极贝vv", profile.nickname)
        assertTrue(profile.avatarUrl.isNotBlank())
        assertTrue(profile.metrics.any { it.label == "关注" && it.value == "126" })
    }
}
