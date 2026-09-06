package com.local.douyindownloader

import org.json.JSONObject

/** A creator can be identified by its own returned media; recommendations are not identity sources. */
internal object InstagramCreatorFeedProfile {
    fun findOwner(connection: JSONObject, handle: String): JSONObject? {
        val edges = connection.optJSONArray("edges") ?: throw CreatorSourceException("RESPONSE_CHANGED", "Instagram 列表缺少作品数据")
        val matches = edges.values().filterIsInstance<JSONObject>().mapNotNull { edge ->
            val node = edge.optJSONObject("node") ?: return@mapNotNull null
            val item = node.optJSONObject("media") ?: node
            if (item.optBoolean("is_ad") || item.has("injected")) return@mapNotNull null
            val owner = item.optJSONObject("user") ?: item.optJSONObject("owner") ?: return@mapNotNull null
            owner.takeIf { it.optString("username").equals(handle, true) &&
                it.firstValue("pk", "id")?.toString().orEmpty().matches(Regex("[0-9]+")) }
        }
        if (matches.map { it.firstValue("pk", "id").toString() }.distinct().size > 1) {
            throw CreatorSourceException("AUTHOR_CHANGED", "Instagram 返回了冲突的作者身份，不能安全关联作品")
        }
        return matches.maxByOrNull { owner ->
            listOf("full_name", "profile_pic_url", "biography").count { owner.optString(it).isNotBlank() }
        }
    }

    fun fromConnection(connection: JSONObject, handle: String): CreatorProfile {
        val owner = findOwner(connection, handle) ?: throw CreatorSourceException("CREATOR_IDENTITY_UNAVAILABLE",
            "Instagram 列表未提供可确认的目标作者作品，可能为空、受限或响应有变化；不能判断账号是否删除")
        // Cosmetic metadata is optional; the stable ID and exact username are not.
        return InstagramCreatorNormalizer.profile(owner, handle)
    }
}
