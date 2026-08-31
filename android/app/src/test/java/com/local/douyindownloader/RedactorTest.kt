package com.local.douyindownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun redactsJsonSecretsAndUrlQueries() {
        val source = """{"cookie":"secret-value","url":"https://cdn.example/video?a_bogus=abc&x=1"}"""

        val result = Redactor.sanitize(source)

        assertFalse(result.contains("secret-value"))
        assertFalse(result.contains("abc"))
        assertTrue(result.contains("<redacted>"))
    }
}
