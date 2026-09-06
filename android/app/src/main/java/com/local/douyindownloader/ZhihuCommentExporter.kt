package com.local.douyindownloader

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

internal data class ZhihuCommentExportResult(
    val count: Int,
    val incomplete: Boolean,
    val warning: String = "",
)

@Singleton
internal class ZhihuCommentExporter @Inject constructor(
    private val http: ParserHttpClient,
) {
    fun export(
        request: ZhihuCommentRequest,
        cookieHeader: String,
        target: File,
    ): ZhihuCommentExportResult {
        val body = File(target.parentFile, "${target.name}.body")
        val seen = linkedSetOf<String>()
        var warning = ""
        body.bufferedWriter(Charsets.UTF_8).use { writer ->
            try {
                var offset = 0
                var hasMore = true
                while (hasMore) {
                    val page = requestPage(
                        "https://www.zhihu.com/api/v4/answers/${request.answerId}/root_comments" +
                            "?order=normal&limit=$PAGE_SIZE&offset=$offset&status=open",
                        request.answerId,
                        cookieHeader,
                    )
                    val comments = page.optJSONArray("data") ?: JSONArray()
                    for (index in 0 until comments.length()) {
                        val comment = comments.optJSONObject(index) ?: continue
                        val commentId = comment.opt("id")?.toString().orEmpty()
                        if (commentId.isBlank() || !seen.add(commentId)) continue
                        writer.append(renderRootComment(comment))
                        val embedded = comment.optJSONArray("child_comments") ?: JSONArray()
                        for (childIndex in 0 until embedded.length()) {
                            val child = embedded.optJSONObject(childIndex) ?: continue
                            writeChild(writer, child, seen)
                        }
                        val expectedChildren = comment.optInt("child_comment_count").coerceAtLeast(0)
                        if (expectedChildren > embedded.length()) {
                            writeRemainingChildren(
                                writer,
                                commentId,
                                embedded.length(),
                                cookieHeader,
                                seen,
                            )
                        }
                    }
                    val paging = page.optJSONObject("paging")
                    hasMore = paging?.optBoolean("is_end", comments.length() == 0) == false
                    val next = paging?.optString("next").orEmpty()
                    offset = offsetFromUrl(next).takeIf { it > offset }
                        ?: offset + comments.length().coerceAtLeast(PAGE_SIZE)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                warning = Redactor.sanitize(error.message ?: error.javaClass.simpleName)
            }
        }
        target.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append("# 评论（已保存 ${seen.size} 条）\n\n")
            if (warning.isNotBlank()) {
                writer.append("> ⚠ 评论获取不完整：").append(escapeInline(warning)).append("\n\n")
            } else if (seen.isEmpty()) {
                writer.append("下载时未发现当前账号可见的评论。\n")
            }
            if (body.exists()) body.bufferedReader(Charsets.UTF_8).use { it.copyTo(writer) }
        }
        body.delete()
        return ZhihuCommentExportResult(seen.size, warning.isNotBlank(), warning)
    }

    private fun writeRemainingChildren(
        writer: Appendable,
        rootCommentId: String,
        initialOffset: Int,
        cookieHeader: String,
        seen: MutableSet<String>,
    ) {
        var offset = initialOffset
        var hasMore = true
        while (hasMore) {
            val page = requestPage(
                "https://www.zhihu.com/api/v4/comments/$rootCommentId/child_comments" +
                    "?limit=$PAGE_SIZE&offset=$offset",
                rootCommentId,
                cookieHeader,
            )
            val comments = page.optJSONArray("data") ?: JSONArray()
            for (index in 0 until comments.length()) {
                comments.optJSONObject(index)?.let { writeChild(writer, it, seen) }
            }
            val paging = page.optJSONObject("paging")
            hasMore = paging?.optBoolean("is_end", comments.length() == 0) == false
            val next = paging?.optString("next").orEmpty()
            offset = offsetFromUrl(next).takeIf { it > offset }
                ?: offset + comments.length().coerceAtLeast(PAGE_SIZE)
        }
    }

    private fun writeChild(writer: Appendable, comment: JSONObject, seen: MutableSet<String>) {
        val id = comment.opt("id")?.toString().orEmpty()
        if (id.isBlank() || !seen.add(id)) return
        writer.append(renderChildComment(comment))
    }

    private fun requestPage(url: String, refererId: String, cookieHeader: String): JSONObject {
        val response = http.get(
            url,
            mapOf(
                "User-Agent" to ZhihuPlatformParser.USER_AGENT,
                "Referer" to "https://www.zhihu.com/answer/$refererId",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
            ),
            cookieHeader,
            30,
        )
        if (response.statusCode !in 200..299) {
            val message = if (response.statusCode in setOf(401, 403, 429)) {
                "知乎登录状态或风控拒绝了评论请求（HTTP ${response.statusCode}）"
            } else {
                "知乎评论请求失败（HTTP ${response.statusCode}）"
            }
            error(message)
        }
        return JSONObject(response.body)
    }

    private fun renderRootComment(comment: JSONObject): String = buildString {
        append("## ").append(escapeInline(authorName(comment)))
        metadata(comment).takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
        append("\n\n").append(commentText(comment)).append("\n\n")
    }

    private fun renderChildComment(comment: JSONObject): String = buildString {
        val replyTo = comment.optJSONObject("reply_to_author")?.optString("name").orEmpty()
        append("> **").append(escapeInline(authorName(comment)))
        if (replyTo.isNotBlank()) append(" 回复 ").append(escapeInline(replyTo))
        metadata(comment).takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
        append("**  \n")
        commentText(comment).lines().forEach { line -> append("> ").append(line).append('\n') }
        append("\n")
    }

    private fun authorName(comment: JSONObject): String =
        comment.optJSONObject("author")?.optJSONObject("member")?.optString("name").orEmpty()
            .ifBlank { comment.optJSONObject("author")?.optString("name").orEmpty() }
            .ifBlank { "匿名用户" }

    private fun metadata(comment: JSONObject): String = buildList {
        val timestamp = comment.optLong("created_time")
        if (timestamp > 0L) {
            add(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(timestamp * 1_000L)))
        }
        val votes = comment.optInt("vote_count").coerceAtLeast(0)
        if (votes > 0) add("$votes 赞")
    }.joinToString(" · ")

    private fun commentText(comment: JSONObject): String {
        val html = comment.optString("content")
        val text = Jsoup.parseBodyFragment(html.replace("<br>", "\n", ignoreCase = true))
            .wholeText()
            .trim()
        return text.lines().joinToString("\n") { line -> escapeText(line) }
    }

    private fun offsetFromUrl(url: String): Int = url.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=') == "offset" }
        ?.substringAfter('=')
        ?.toIntOrNull()
        ?: -1

    private fun escapeInline(value: String): String = value
        .replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("`", "\\`")
        .replace("[", "\\[")
        .replace("]", "\\]")

    private fun escapeText(value: String): String = escapeInline(value)
        .let { if (it.startsWith("#") || it.startsWith(">") || it.startsWith("- ")) "\\$it" else it }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
