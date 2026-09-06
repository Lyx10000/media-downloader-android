package com.local.douyindownloader

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InstagramCreatorDiagnosticsTest {
    private fun response(
        body: String = "{}",
        status: Int = 429,
        headers: Map<String, String> = emptyMap(),
        url: String = "https://www.instagram.com/api/v1/users/web_profile_info/?username=private-name",
    ) = ParserHttpResponse(status, url, emptyList(), body, headers)

    @Test fun `request logs booleans not credential values and survives global redaction`() {
        val fields = InstagramCreatorDiagnosticFields.request("profile",
            "sessionid=session-secret; csrftoken=csrf-secret; ds_user_id=123456789",
            mapOf("X-CSRFToken" to "csrf-secret", "User-Agent" to SOCIAL_CREATOR_UA, "X-IG-App-ID" to "app-secret"), false)
        val saved = JSONObject(Redactor.sanitize(fields.toString()))
        assertTrue(saved.getBoolean("session_present"))
        assertTrue(saved.getBoolean("csrf_present"))
        assertTrue(saved.getBoolean("viewer_id_present"))
        assertTrue(saved.getBoolean("csrf_consistent"))
        assertEquals("not_checked", saved.getString("credential_validity"))
        listOf("session-secret", "csrf-secret", "123456789", "app-secret").forEach { assertFalse(saved.toString().contains(it)) }
    }

    @Test fun `stored unrelated cookies not called a logged in session`() {
        val fields = InstagramCreatorDiagnosticFields.request("profile", "device=x", emptyMap(), false)
        assertTrue(fields.getBoolean("credential_present"))
        assertFalse(fields.getBoolean("session_present"))
        assertFalse(fields.getBoolean("csrf_consistent"))
        assertEquals("not_checked", fields.getString("credential_validity"))
    }

    @Test fun `429 details recorded before error classification without private data`() {
        val fields = InstagramCreatorDiagnosticFields.response(response(
            body = """{"message":"Please wait a few minutes before you try again.","require_login":true,"status":"fail","sessionid":"SECRET","challenge":{"url":"https://www.instagram.com/challenge/private-id"}}""",
            headers = mapOf("Retry-After" to "120", "Content-Type" to "application/json; charset=utf-8", "Set-Cookie" to "sessionid=SECRET")))
        assertEquals(429, fields.getInt("http_status"))
        assertTrue(fields.getBoolean("require_login"))
        assertEquals("please_wait", fields.getString("message_kind"))
        assertEquals(120L, fields.getLong("retry_after_seconds"))
        assertTrue(fields.getBoolean("challenge_present"))
        assertFalse(fields.toString().contains("SECRET"))
        assertFalse(fields.toString().contains("private-id"))
        assertFalse(fields.toString().contains("private-name"))
    }

    @Test fun `HTTP date retry uses server date instead of inaccurate phone clock`() {
        val fields = InstagramCreatorDiagnosticFields.response(response(headers = mapOf(
            "retry-after" to "Sun, 06 Sep 2026 10:02:00 GMT",
            "Date" to "Sun, 06 Sep 2026 10:00:00 GMT")), Instant.parse("2030-01-01T00:00:00Z"))
        assertEquals("http_date", fields.getString("retry_after_format"))
        assertEquals("server", fields.getString("retry_after_clock"))
        assertEquals(120L, fields.getLong("retry_after_seconds"))
    }

    @Test fun `no waiting time is fabricated when response omits Retry After`() {
        val fields = InstagramCreatorDiagnosticFields.response(response())
        assertFalse(fields.getBoolean("retry_after_present"))
        assertFalse(fields.has("retry_after_seconds"))
        val invalid = InstagramCreatorDiagnosticFields.response(response(headers = mapOf("Retry-After" to "sensitive-server-value")))
        assertEquals("unrecognized", invalid.getString("retry_after_format"))
        assertFalse(invalid.toString().contains("sensitive-server-value"))
    }

    @Test fun `unrecognized server messages and HTML are not dumped into log`() {
        val fields = InstagramCreatorDiagnosticFields.response(response(body = """{"message":"email=user@example.com credential=SECRET","errors":[{"code":88,"message":"login_required SECRET"}]}"""))
        assertEquals("other_redacted", fields.getString("message_kind"))
        assertEquals(88, fields.getJSONArray("server_errors").getJSONObject(0).getInt("code"))
        assertFalse(fields.toString().contains("SECRET"))
        assertFalse(fields.toString().contains("user@example.com"))
        val html = InstagramCreatorDiagnosticFields.response(response(body = "<html>SECRET</html>",
            url = "https://www.instagram.com/accounts/login/?next=SECRET"))
        assertEquals("html", html.getString("body_format"))
        assertEquals("login", html.getString("final_route"))
        assertFalse(html.toString().contains("SECRET"))
    }
}
