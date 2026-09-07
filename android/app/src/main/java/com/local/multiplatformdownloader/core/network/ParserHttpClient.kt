package com.local.multiplatformdownloader.core.network

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

internal data class ParserHttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val redirectUrls: List<String>,
    val body: String,
    val headers: Map<String, String>,
)

internal interface ParserHttpClient {
    /** A single hop, for flows which must validate a redirect before sending credentials. */
    fun getWithoutRedirects(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String = "",
        timeoutSeconds: Long = 25,
    ): ParserHttpResponse = throw UnsupportedOperationException("Single-hop GET is not supported")

    @Throws(IOException::class)
    fun get(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String = "",
        timeoutSeconds: Long = 25,
    ): ParserHttpResponse

    @Throws(IOException::class)
    fun post(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String = "",
        body: ByteArray = byteArrayOf(),
        timeoutSeconds: Long = 25,
    ): ParserHttpResponse = throw UnsupportedOperationException("POST is not supported")

    @Throws(IOException::class)
    fun probeContentLength(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long = 5,
    ): Long
}

@Singleton
internal class OkHttpParserClient @Inject constructor() : ParserHttpClient {
    private val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun getWithoutRedirects(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String,
        timeoutSeconds: Long,
    ): ParserHttpResponse {
        val client = baseClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return client.newCall(request(url, headers, cookieHeader)).execute().use { response ->
            val body = response.body
            if (body != null && body.source().request(4L * 1024 * 1024 + 1)) {
                throw IOException("Parser response exceeds size limit")
            }
            toParserResponse(response)
        }
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String,
        timeoutSeconds: Long,
    ): ParserHttpResponse {
        val request = request(url, headers, cookieHeader)
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return client.newCall(request).execute().use { response ->
            val redirects = generateSequence(response.priorResponse) { it.priorResponse }
                .map { it.request.url.toString() }
                .toList()
                .asReversed()
            ParserHttpResponse(
                statusCode = response.code,
                finalUrl = response.request.url.toString(),
                redirectUrls = redirects,
                body = response.body?.string().orEmpty(),
                headers = response.headers.names().associateWith { name -> response.header(name).orEmpty() },
            )
        }
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String,
        body: ByteArray,
        timeoutSeconds: Long,
    ): ParserHttpResponse {
        val request = requestBuilder(url, headers, cookieHeader)
            .post(body.toRequestBody(null))
            .build()
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return client.newCall(request).execute().use(::toParserResponse)
    }

    override fun probeContentLength(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): Long {
        val request = request(
            url,
            headers + mapOf("Range" to "bytes=0-0", "Accept-Encoding" to "identity"),
            cookieHeader = "",
        )
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        return client.newCall(request).execute().use { response ->
            CONTENT_RANGE.find(response.header("Content-Range").orEmpty())
                ?.groupValues?.get(1)
                ?.takeUnless { it == "*" }
                ?.toLongOrNull()
                ?: if (response.code == 200) response.header("Content-Length")?.toLongOrNull() ?: 0L else 0L
        }
    }

    private fun request(url: String, headers: Map<String, String>, cookieHeader: String): Request =
        requestBuilder(url, headers, cookieHeader).build()

    private fun requestBuilder(
        url: String,
        headers: Map<String, String>,
        cookieHeader: String,
    ): Request.Builder = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
        }

    private fun toParserResponse(response: okhttp3.Response): ParserHttpResponse {
        val redirects = generateSequence(response.priorResponse) { it.priorResponse }
            .map { it.request.url.toString() }
            .toList()
            .asReversed()
        return ParserHttpResponse(
            statusCode = response.code,
            finalUrl = response.request.url.toString(),
            redirectUrls = redirects,
            body = response.body?.string().orEmpty(),
            headers = response.headers.names().associateWith { name -> response.header(name).orEmpty() },
        )
    }

    companion object {
        private val CONTENT_RANGE = Regex("bytes\\s+\\d+-\\d+/(\\d+|\\*)", RegexOption.IGNORE_CASE)
    }
}

internal class ParserHttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode")
