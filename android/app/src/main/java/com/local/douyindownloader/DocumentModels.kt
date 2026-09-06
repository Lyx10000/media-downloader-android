package com.local.douyindownloader

import org.json.JSONArray
import org.json.JSONObject

enum class DocumentType(val wireValue: String, val fileName: String) {
    QUESTION("question", "index.md"),
    ARTICLE("article", "article.md"),
    ANSWER("answer", "answer.md"),
    PIN("pin", "pin.md");

    val displayLabel: String
        get() = when (this) {
            QUESTION -> "问题"
            ARTICLE -> "文章"
            ANSWER -> "回答"
            PIN -> "想法"
        }

    companion object {
        fun fromWire(value: String): DocumentType = entries.firstOrNull {
            it.wireValue.equals(value, ignoreCase = true)
        } ?: ARTICLE
    }
}

enum class DocumentBlockType(val wireValue: String) {
    PARAGRAPH("paragraph"),
    HEADING("heading"),
    LIST("list"),
    BLOCKQUOTE("blockquote"),
    CODE("code"),
    IMAGE("image"),
    VIDEO("video"),
    HTML("html");

    companion object {
        fun fromWire(value: String): DocumentBlockType = entries.firstOrNull {
            it.wireValue.equals(value, ignoreCase = true)
        } ?: PARAGRAPH
    }
}

data class DocumentBlock(
    val type: DocumentBlockType,
    val text: String = "",
    val assetId: String = "",
    val level: Int = 0,
    val language: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("type", type.wireValue)
        put("text", text)
        put("asset_id", assetId)
        put("level", level)
        put("language", language)
    }

    companion object {
        fun fromJson(value: JSONObject) = DocumentBlock(
            type = DocumentBlockType.fromWire(value.optString("type")),
            text = value.optString("text"),
            assetId = value.optString("asset_id"),
            level = value.optInt("level"),
            language = value.optString("language"),
        )
    }
}

enum class DocumentAssetKind(val wireValue: String) {
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun fromWire(value: String): DocumentAssetKind = entries.firstOrNull {
            it.wireValue.equals(value, ignoreCase = true)
        } ?: IMAGE
    }
}

data class DocumentAsset(
    val id: String,
    val kind: DocumentAssetKind,
    val sourceId: String = "",
    val candidateUrls: List<String> = emptyList(),
    val variants: List<MediaVariant> = emptyList(),
    val coverUrls: List<String> = emptyList(),
    val alt: String = "",
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("kind", kind.wireValue)
        put("source_id", sourceId)
        put("candidate_urls", JSONArray(candidateUrls))
        put("variants", JSONArray().apply { variants.forEach { put(it.toJson()) } })
        put("cover_urls", JSONArray(coverUrls))
        put("alt", alt)
    }

    companion object {
        fun fromJson(value: JSONObject): DocumentAsset {
            val variants = value.optJSONArray("variants").jsonObjects().map { item ->
                val size = item.optLong("size")
                MediaVariant(
                    width = item.optInt("width"),
                    height = item.optInt("height"),
                    bitrate = item.optInt("bitrate"),
                    fps = item.optInt("fps"),
                    codec = item.optString("codec"),
                    size = size,
                    sizeSource = item.optString(
                        "size_source",
                        if (size > 0L) "api" else "unknown",
                    ),
                    urls = item.optJSONArray("urls").jsonStrings(),
                )
            }
            return DocumentAsset(
                id = value.optString("id"),
                kind = DocumentAssetKind.fromWire(value.optString("kind")),
                sourceId = value.optString("source_id"),
                candidateUrls = value.optJSONArray("candidate_urls").jsonStrings(),
                variants = variants,
                coverUrls = value.optJSONArray("cover_urls").jsonStrings(),
                alt = value.optString("alt"),
            )
        }
    }
}

data class DocumentContent(
    val type: DocumentType,
    val title: String,
    val author: String,
    val sourceUrl: String,
    val blocks: List<DocumentBlock>,
    val assets: List<DocumentAsset>,
    val warnings: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().apply {
        put("type", type.wireValue)
        put("title", title)
        put("author", author)
        put("source_url", sourceUrl)
        put("blocks", JSONArray().apply { blocks.forEach { put(it.toJson()) } })
        put("assets", JSONArray().apply { assets.forEach { put(it.toJson()) } })
        put("warnings", JSONArray(warnings))
    }

    companion object {
        fun fromJson(value: JSONObject) = DocumentContent(
            type = DocumentType.fromWire(value.optString("type")),
            title = value.optString("title"),
            author = value.optString("author"),
            sourceUrl = value.optString("source_url"),
            blocks = value.optJSONArray("blocks").jsonObjects().map(DocumentBlock::fromJson),
            assets = value.optJSONArray("assets").jsonObjects().map(DocumentAsset::fromJson),
            warnings = value.optJSONArray("warnings").jsonStrings(),
        )
    }
}

private fun JSONArray?.jsonStrings(): List<String> = if (this == null) {
    emptyList()
} else {
    (0 until length()).mapNotNull { index -> optString(index).takeIf(String::isNotBlank) }
}

private fun JSONArray?.jsonObjects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    (0 until length()).mapNotNull(::optJSONObject)
}
