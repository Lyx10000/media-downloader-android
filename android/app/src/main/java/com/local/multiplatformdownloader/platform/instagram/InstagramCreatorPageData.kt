package com.local.multiplatformdownloader.platform.instagram

import com.local.multiplatformdownloader.core.model.SourcePlatform
import com.local.multiplatformdownloader.core.model.WebPageSnapshot
import com.local.multiplatformdownloader.feature.creator.CreatorSourceException
import com.local.multiplatformdownloader.feature.creator.socialCreatorHandle
import com.local.multiplatformdownloader.core.network.firstValue

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/** Read only exact-target data. Mobile pages also embed user objects inside encoded JSON strings. */
internal object InstagramCreatorPageData {
    private const val MAX_CHARS = 4_000_000

    fun fromHtml(html: String, handle: String, observe: (JSONObject) -> Unit = {}): JSONObject? {
        if (html.length > MAX_CHARS) {
            runCatching { observe(JSONObject().put("outcome", "html_limit").put("html_chars", html.length)) }
            return null
        }
        val states = JSONArray()
        Jsoup.parse(html).select("script[type=application/json], script[data-sjs]").forEach {
            states.put(it.data())
        }
        return find(states, handle) { fields ->
            fields.put("html_chars", html.length).put("script_count", states.length())
            observe(fields)
        }
    }

    fun fromSnapshot(snapshot: WebPageSnapshot, handle: String, observe: (JSONObject) -> Unit = {}): JSONObject {
        val capture = JSONObject().put("initial_chars", snapshot.initialData.length)
            .put("visible_chars", snapshot.visibleText.length)
            .put("route_matches", matchesPage(snapshot.finalUrl, handle))
        val metadata = runCatching { JSONObject(snapshot.captureDiagnostics) }.getOrNull()
        // Whitelist scalar measurements; never log DOM text, credentials, IDs or arbitrary metadata.
        listOf("script_count", "parsed_script_count", "invalid_script_count", "skipped_script_count",
            "captured_chars", "input_count", "body_height", "page_elapsed_ms").forEach { key ->
            (metadata?.opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }?.let { capture.put(key, it) }
        }
        capture.put("ready_state", metadata?.optString("ready_state")
            ?.takeIf { it in setOf("loading", "interactive", "complete") } ?: "unknown")
        if (!matchesPage(snapshot.finalUrl, handle)) {
            runCatching { observe(capture.put("outcome", "route_mismatch")) }
            throw CreatorSourceException("LOGIN_REQUIRED", "Instagram 未停留在目标作者主页，请检查登录或验证状态")
        }
        return find(snapshot.initialData, handle) { fields ->
            capture.keys().forEach { fields.put(it, capture.opt(it)) }
            observe(fields)
        }
            ?: throw CreatorSourceException("RESPONSE_CHANGED", "Instagram 网页没有返回目标作者资料，请在登录环境确认可访问后重试；不会自动反复请求")
    }

    fun matchesPage(url: String, handle: String): Boolean = runCatching {
        socialCreatorHandle(SourcePlatform.INSTAGRAM, url).equals(handle, true)
    }.getOrDefault(false)

    fun find(root: Any?, handle: String, observe: (JSONObject) -> Unit = {}): JSONObject? {
        var visited = 0
        var decodedChars = 0
        var decodedStrings = 0
        var invalidJson = 0
        var targetMatches = 0
        var validIdMatches = 0
        var pkMatches = 0
        var idOnlyMatches = 0
        var differingIdFields = 0
        var depthLimit = false
        var nodeLimit = false
        var charLimit = false
        var best: JSONObject? = null
        var bestScore = -1
        val identities = mutableSetOf<String>()
        fun walk(value: Any?, depth: Int) {
            if (depth > 45) { depthLimit = true; return }
            if (++visited > 100_000) { nodeLimit = true; return }
            when (value) {
                is String -> {
                    val text = value.trim()
                    if (text.length > MAX_CHARS - decodedChars) { charLimit = true; return }
                    if (!(text.startsWith('{') || text.startsWith('['))) return
                    decodedChars += text.length
                    val parsed = runCatching { JSONTokener(text).nextValue() }.getOrNull()
                    if (parsed == null) invalidJson++ else { decodedStrings++; walk(parsed, depth + 1) }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
                is JSONObject -> {
                    val id = value.firstValue("pk", "id")?.toString().orEmpty()
                    if (value.optString("username").equals(handle, true)) targetMatches++
                    if (value.optString("username").equals(handle, true) && id.matches(Regex("[0-9]+"))) {
                        validIdMatches++
                        if (value.firstValue("pk") != null) pkMatches++ else idOnlyMatches++
                        val pk = value.firstValue("pk")?.toString().orEmpty()
                        val graphId = value.firstValue("id")?.toString().orEmpty()
                        if (pk.isNotBlank() && graphId.isNotBlank() && pk != graphId) differingIdFields++
                        identities += id
                        val score = listOf("full_name", "biography", "follower_count", "following_count",
                            "edge_followed_by", "edge_follow", "profile_pic_url", "is_private",
                            "edge_owner_to_timeline_media").count { value.has(it) && !value.isNull(it) }
                        if (score > bestScore) { best = value; bestScore = score }
                    }
                    value.keys().forEach { walk(value.opt(it), depth + 1) }
                }
            }
        }
        walk(root, 0)
        runCatching {
            observe(JSONObject().put("outcome", when {
                identities.size > 1 -> "identity_conflict"
                targetMatches == 0 -> "target_missing"
                identities.isEmpty() -> "stable_identity_missing"
                bestScore < 2 -> "profile_fields_insufficient"
                else -> "matched"
            }).put("visited_nodes", visited).put("decoded_chars", decodedChars)
                .put("decoded_strings", decodedStrings).put("invalid_json_count", invalidJson)
                .put("target_match_count", targetMatches).put("valid_identity_match_count", validIdMatches)
                .put("distinct_identity_count", identities.size).put("pk_match_count", pkMatches)
                .put("id_only_match_count", idOnlyMatches).put("differing_id_fields_count", differingIdFields)
                .put("best_field_count", bestScore).put("depth_limit_hit", depthLimit)
                .put("node_limit_hit", nodeLimit).put("char_limit_hit", charLimit))
        }
        // Route params and recommendation stubs alone are not a usable profile.
        return best.takeIf { identities.size == 1 && bestScore >= 2 }
    }
}
