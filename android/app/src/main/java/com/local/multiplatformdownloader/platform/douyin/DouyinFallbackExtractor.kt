package com.local.multiplatformdownloader.platform.douyin

import com.local.multiplatformdownloader.core.network.firstArray
import com.local.multiplatformdownloader.core.network.firstString
import com.local.multiplatformdownloader.core.network.keysInOrder
import com.local.multiplatformdownloader.core.network.values

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Extracts one exact Douyin work from the response shapes used by the public
 * web, share and mobile-feed entry points. Every candidate is matched by ID;
 * recommendation items are never accepted as a fallback.
 */
internal object DouyinFallbackExtractor {
    private const val MAX_VISITED_NODES = 50_000
    private const val MAX_DEPTH = 12

    fun findExactDetail(root: JSONObject, itemId: String): JSONObject? {
        val queue = ArrayDeque<Node>()
        queue.add(Node(root, 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES) {
            val (value, depth) = queue.removeFirst()
            visited += 1
            when (value) {
                is JSONObject -> {
                    val id = value.firstString("aweme_id", "awemeId", "item_id", "itemId")
                    if (id == itemId && looksLikeWork(value)) return normalizeDetail(value)
                    if (depth < MAX_DEPTH) {
                        value.keysInOrder().forEach { key ->
                            when (val child = value.opt(key)) {
                                is JSONObject, is JSONArray -> queue.add(Node(child, depth + 1))
                            }
                        }
                    }
                }
                is JSONArray -> if (depth < MAX_DEPTH) {
                    value.values().forEach { child ->
                        if (child is JSONObject || child is JSONArray) queue.add(Node(child, depth + 1))
                    }
                }
            }
        }
        return null
    }

    fun findExactDetailFromBody(body: String, itemId: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) {
            runCatching { JSONObject(trimmed) }.getOrNull()?.let { root ->
                findExactDetail(root, itemId)?.let { return it }
            }
        }
        return findExactDetailFromPage(body, itemId)
    }

    fun findExactDetailFromPage(page: String, itemId: String): JSONObject? {
        val decoded = decodePage(page)
        MARKERS.forEach { marker ->
            var from = 0
            while (from < decoded.length) {
                val markerIndex = decoded.indexOf(marker, from)
                if (markerIndex < 0) break
                val start = decoded.indexOf('{', markerIndex + marker.length)
                if (start < 0) break
                extractBalancedObject(decoded, start).takeIf(String::isNotBlank)?.let { objectText ->
                    runCatching { JSONObject(objectText) }.getOrNull()?.let { root ->
                        findExactDetail(root, itemId)?.let { return it }
                    }
                }
                from = markerIndex + marker.length
            }
        }

        val patterns = listOf(
            "\"aweme_id\":\"$itemId\"",
            "\"aweme_id\":$itemId",
            "\"awemeId\":\"$itemId\"",
        )
        patterns.forEach { pattern ->
            var match = decoded.indexOf(pattern)
            while (match >= 0) {
                var start = decoded.lastIndexOf('{', match)
                var attempts = 0
                while (start >= 0 && attempts < 16) {
                    val objectText = extractBalancedObject(decoded, start)
                    if (objectText.isNotBlank()) {
                        runCatching { JSONObject(objectText) }.getOrNull()?.let { root ->
                            findExactDetail(root, itemId)?.let { return it }
                        }
                    }
                    start = decoded.lastIndexOf('{', start - 1)
                    attempts += 1
                }
                match = decoded.indexOf(pattern, match + pattern.length)
            }
        }
        return null
    }

    private fun looksLikeWork(value: JSONObject): Boolean =
        value.has("video") || value.has("images") || value.has("image_post_info") ||
            value.has("desc") || value.has("author")

    private fun normalizeDetail(value: JSONObject): JSONObject {
        if (value.optJSONArray("images") != null) return value
        val imageList = value.optJSONObject("image_post_info")
            ?.firstArray("image_list", "imageList")
            ?: return value
        return JSONObject(value.toString()).put("images", imageList)
    }

    private fun decodePage(page: String): String = page
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&amp;", "&")
        .replace("\\\"", "\"")
        .replace("\\/", "/")

    private fun extractBalancedObject(text: String, start: Int): String {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
                continue
            }
            when (character) {
                '\'', '"' -> quote = character
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return ""
    }

    private data class Node(val value: Any, val depth: Int)

    private val MARKERS = listOf(
        "window._ROUTER_DATA",
        "window.__INITIAL_STATE__",
        "__INITIAL_STATE__",
        "__NEXT_DATA__",
    )
}
