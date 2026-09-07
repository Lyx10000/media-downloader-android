package com.local.multiplatformdownloader.platform.instagram


import com.local.multiplatformdownloader.core.logging.DiagnosticLogger
import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.feature.creator.CreatorPage
import com.local.multiplatformdownloader.feature.creator.SOCIAL_CREATOR_UA
import com.local.multiplatformdownloader.feature.creator.socialCookie
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** Observe an existing request exactly once. Diagnostics must never change its result or retry it. */
@Singleton
internal class InstagramCreatorDiagnostics @Inject constructor(private val logger: DiagnosticLogger) {
    fun feedPage(page: CreatorPage, reused: Boolean) {
        runCatching {
            logger.event("creator-search", "CREATOR", "INSTAGRAM_FEED_PAGE_READY", JSONObject().apply {
                put("strategy", "feed_first_v1")
                put("page", page.pageNumber)
                put("works", page.works.size)
                put("has_more", page.hasMore)
                put("reused_first_page", reused)
            })
        }
    }

    fun profileState(fields: JSONObject, fromWebView: Boolean) {
        runCatching {
            logger.event("creator-search", "CREATOR", "INSTAGRAM_PROFILE_STATE_DIAGNOSTIC", fields.apply {
                put("diagnostic_version", "instagram-profile-state-v1")
                put("source", if (fromWebView) "webview_state" else "html_state")
            })
        }
    }

    fun profileExtracted(user: JSONObject, fromWebView: Boolean) {
        runCatching {
            logger.event("creator-search", "CREATOR", "INSTAGRAM_PROFILE_EXTRACTED", JSONObject().apply {
                put("source", if (fromWebView) "webview_state" else "html_state")
                put("identity_field", if (user.has("pk")) "pk" else "id")
                put("author_present", user.optString("full_name").isNotBlank())
                put("avatar_present", user.optString("profile_pic_url").isNotBlank() || user.optString("profile_pic_url_hd").isNotBlank())
                put("first_page_present", user.has("edge_owner_to_timeline_media"))
            })
        }
    }

    fun request(
        operation: String,
        cookie: String,
        headers: Map<String, String>,
        hasCursor: Boolean = false,
        execute: () -> ParserHttpResponse,
    ): ParserHttpResponse {
        val requestId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        fun emit(event: String, extra: JSONObject = JSONObject()) {
            // No URL query, body, arbitrary header, credential value, or exception message is logged.
            runCatching {
                val fields = InstagramCreatorDiagnosticFields.request(operation, cookie, headers, hasCursor)
                fields.put("request_id", requestId)
                fields.put("elapsed_ms", ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0))
                extra.keys().forEach { fields.put(it, extra.get(it)) }
                logger.event("creator-search", "CREATOR_HTTP", event, fields)
            }
        }
        emit("INSTAGRAM_REQUEST_STARTED")
        val response = try {
            execute()
        } catch (error: IOException) {
            emit("INSTAGRAM_REQUEST_NETWORK_FAILED", JSONObject().put("failure_kind", when (error) {
                is java.net.SocketTimeoutException -> "timeout"
                is java.net.UnknownHostException -> "dns"
                is javax.net.ssl.SSLException -> "tls"
                else -> "io"
            }))
            throw error
        }
        // Capture 429/401 details before the parser throws away the response body.
        runCatching { emit("INSTAGRAM_RESPONSE_RECEIVED", InstagramCreatorDiagnosticFields.response(response)) }
        return response
    }
}

internal object InstagramCreatorDiagnosticFields {
    private const val VERSION = "instagram-creator-http-v2"

    fun request(operation: String, cookie: String, headers: Map<String, String>, hasCursor: Boolean) = JSONObject().apply {
        put("diagnostic_version", VERSION)
        put("platform", "instagram")
        put("operation", operation.takeIf { it in setOf("profile", "profile_web", "posts", "reels") } ?: "unknown")
        put("endpoint", when (operation) {
            "profile" -> "/api/v1/users/web_profile_info/"
            "profile_web" -> "/{username}/"
            else -> "/graphql/query/"
        })
        put("method", if (operation in setOf("profile", "profile_web")) "GET" else "POST")
        put("has_cursor", hasCursor)
        put("credential_present", cookie.isNotBlank())
        put("session_present", socialCookie(cookie, "sessionid").isNotBlank())
        put("csrf_present", socialCookie(cookie, "csrftoken").isNotBlank())
        put("viewer_id_present", socialCookie(cookie, "ds_user_id").isNotBlank())
        val csrfHeader = header(headers, "x-csrftoken")
        put("csrf_header_present", csrfHeader.isNotBlank())
        put("csrf_consistent", csrfHeader.isNotBlank() && csrfHeader == socialCookie(cookie, "csrftoken"))
        put("credential_validity", "not_checked")
        put("user_agent_profile", if (header(headers, "user-agent") == SOCIAL_CREATOR_UA) "android_mobile_130" else "other")
        put("ig_app_header_present", header(headers, "x-ig-app-id").isNotBlank())
    }

    fun response(response: ParserHttpResponse, now: Instant = Instant.now()): JSONObject = JSONObject().apply {
        put("http_status", response.statusCode)
        put("redirect_count", response.redirectUrls.size)
        val uri = runCatching { URI(response.finalUrl) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        put("final_host", when (host) {
            "www.instagram.com", "instagram.com", "i.instagram.com" -> host
            "" -> "unknown"
            else -> "other"
        })
        val path = uri?.path.orEmpty()
        put("final_route", when {
            path == "/api/v1/users/web_profile_info/" -> "profile_api"
            path == "/graphql/query/" -> "graphql_api"
            path.startsWith("/accounts/login") -> "login"
            path.startsWith("/challenge") || path.startsWith("/checkpoint") -> "challenge"
            path == "/" -> "home"
            else -> "other"
        })
        val contentType = header(response.headers, "content-type").substringBefore(';').trim().lowercase()
        put("content_type", contentType.takeIf { it in setOf("application/json", "text/json", "text/html", "text/plain") } ?: "other")
        put("body_chars", response.body.length)
        val retryAfter = header(response.headers, "retry-after").trim()
        put("retry_after_present", retryAfter.isNotBlank())
        val seconds = retryAfter.takeIf { it.matches(Regex("[0-9]{1,10}")) }?.toLongOrNull()
        val retryDate = parseHttpDate(retryAfter)
        when {
            seconds != null -> {
                put("retry_after_format", "seconds")
                put("retry_after_seconds", seconds)
            }
            retryDate != null -> {
                put("retry_after_format", "http_date")
                put("retry_after_at", retryDate.toString())
                val serverDate = parseHttpDate(header(response.headers, "date"))
                put("retry_after_seconds", (retryDate.epochSecond - (serverDate ?: now).epochSecond).coerceAtLeast(0))
                put("retry_after_clock", if (serverDate != null) "server" else "device")
            }
            retryAfter.isNotBlank() -> put("retry_after_format", "unrecognized")
            else -> put("retry_after_format", "absent")
        }
        // Error JSON is bounded; never copy full successful profile/HTML payloads into logs.
        val root = if (response.body.length <= 256_000) {
            runCatching { JSONObject(response.body.removePrefix("for (;;);")) }.getOrNull()
        } else null
        put("body_format", when {
            root != null -> "json_object"
            response.body.length > 256_000 -> "too_large_to_inspect"
            response.body.trimStart().startsWith('<') -> "html"
            response.body.isBlank() -> "empty"
            else -> "other"
        })
        if (root != null) {
            put("server_status", root.optString("status").takeIf { it in setOf("ok", "fail") } ?: "other_or_missing")
            listOf("require_login", "login_required", "feedback_required", "spam").forEach { field ->
                if (root.has(field) && root.opt(field) is Boolean) put(field, root.getBoolean(field))
            }
            put("challenge_present", root.has("challenge") && !root.isNull("challenge"))
            put("checkpoint_present", root.has("checkpoint_url") && !root.isNull("checkpoint_url"))
            put("data_present", root.has("data") && !root.isNull("data"))
            put("user_present", root.optJSONObject("data")?.optJSONObject("user") != null)
            put("message_kind", messageKind(root.opt("message") as? String ?: ""))
            put("error_type_kind", messageKind(root.opt("error_type") as? String ?: ""))
            val errors = root.optJSONArray("errors") ?: JSONArray()
            put("error_count", errors.length())
            val summaries = JSONArray()
            repeat(minOf(errors.length(), 8)) { index ->
                errors.optJSONObject(index)?.let { error ->
                    summaries.put(JSONObject().apply {
                        val code = error.opt("code")?.toString().orEmpty()
                        if (code.matches(Regex("[0-9]{1,8}"))) put("code", code.toLong())
                        put("message_kind", messageKind(error.opt("message") as? String ?: ""))
                    })
                }
            }
            put("server_errors", summaries)
        }
    }

    /** Only fixed categories are emitted, even if the server reflects an email or credential. */
    private fun messageKind(value: String): String {
        val text = value.lowercase()
        return when {
            text.isBlank() -> "absent"
            "wait a few minutes" in text || "please wait" in text -> "please_wait"
            "rate_limit" in text || "rate limit" in text || "too many requests" in text -> "rate_limited"
            "login_required" in text || "login required" in text -> "login_required"
            "challenge" in text || "checkpoint" in text -> "verification_required"
            "feedback_required" in text -> "feedback_required"
            "not found" in text || "not_found" in text -> "not_found"
            else -> "other_redacted"
        }
    }

    private fun header(headers: Map<String, String>, name: String): String =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()

    private fun parseHttpDate(value: String): Instant? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    }.getOrNull()
}
