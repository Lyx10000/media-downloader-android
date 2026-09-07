package com.local.multiplatformdownloader.core.model

import org.json.JSONArray
import org.json.JSONObject

data class WebPageSnapshot(
    val finalUrl: String,
    val initialData: String = "",
    val title: String = "",
    val author: String = "",
    val contentHtml: String = "",
    val visibleText: String = "",
    val captureDiagnostics: String = "",
) {
    companion object {
        fun fromJavascriptResult(value: String?): WebPageSnapshot? {
            if (value.isNullOrBlank() || value == "null") return null
            val decoded = runCatching { JSONArray("[$value]").optString(0) }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: return null
            val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
            return WebPageSnapshot(
                finalUrl = root.optString("finalUrl"),
                initialData = root.optString("initialData"),
                title = root.optString("title"),
                author = root.optString("author"),
                contentHtml = root.optString("contentHtml"),
                visibleText = root.optString("visibleText"),
                captureDiagnostics = root.optJSONObject("captureDiagnostics")?.toString().orEmpty(),
            ).takeIf { it.finalUrl.isNotBlank() }
        }
    }
}
