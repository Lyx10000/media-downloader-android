package com.local.multiplatformdownloader

import com.local.multiplatformdownloader.core.network.ParserHttpResponse
import com.local.multiplatformdownloader.feature.creator.CreatorSourceException
import com.local.multiplatformdownloader.platform.instagram.requestInstagramProfilePage


import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class InstagramProfileTransportTest {
    @Test fun `closed TLS connection requests browser fallback exactly once and retains cause`() {
        val cause = SSLException("connection closed")
        var calls = 0
        val error = assertThrows(CreatorSourceException::class.java) {
            requestInstagramProfilePage { calls++; throw cause }
        }
        assertEquals("WEB_PROFILE_REQUIRED", error.code)
        assertSame(cause, error.cause)
        assertEquals(1, calls)
    }

    @Test fun `EOF DNS and timeout use the same bounded profile fallback`() {
        listOf(EOFException(), UnknownHostException(), SocketTimeoutException()).forEach { cause ->
            val error = assertThrows(CreatorSourceException::class.java) {
                requestInstagramProfilePage { throw cause }
            }
            assertEquals("WEB_PROFILE_REQUIRED", error.code)
            assertSame(cause, error.cause)
        }
    }

    @Test fun `cancellation interruption and domain failures are not converted into fallback`() {
        listOf(CancellationException(), InterruptedIOException(), CreatorSourceException("LOGIN_REQUIRED", "登录"),
            IllegalStateException()).forEach { cause ->
            val error = assertThrows(cause.javaClass) { requestInstagramProfilePage { throw cause } }
            assertSame(cause, error)
        }
    }

    @Test fun `interrupted thread does not start a new browser request`() {
        val cause = SSLException("connection closed")
        Thread.currentThread().interrupt()
        try {
            assertSame(cause, assertThrows(SSLException::class.java) { requestInstagramProfilePage { throw cause } })
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun `HTTP responses remain unchanged and are not retried`() {
        for (status in listOf(200, 403, 429)) {
            var calls = 0
            val response = ParserHttpResponse(status, "https://www.instagram.com/author/", emptyList(), "", emptyMap())
            assertSame(response, requestInstagramProfilePage { calls++; response })
            assertEquals(1, calls)
        }
    }
}
