package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogFormatterTest {
    @Test
    fun formatsJsonLinesAsIndentedRecords() {
        val formatted = prettyPrintJsonLines(
            """{"event":"START","details":{"count":2}}""" + "\n" +
                """{"event":"DONE","details":{}}""",
        )

        assertTrue(formatted.contains("\n  \"event\": \"START\""))
        assertTrue(formatted.contains("\n  \"details\": {"))
        assertTrue(formatted.contains("\n\n{"))
    }

    @Test
    fun preservesMalformedLinesForDiagnostics() {
        assertEquals("incomplete-json", prettyPrintJsonLines("incomplete-json"))
    }

    @Test
    fun truncatedPreviewKeepsBothBeginningAndEndOfTaskLog() {
        val records = (1..30).joinToString("\n") { index ->
            """{"event":"EVENT_$index","details":{"payload":"${"x".repeat(40)}"}}"""
        }

        val preview = previewJsonLog(records, maxChars = 500)

        assertTrue(preview.contains("EVENT_1"))
        assertTrue(preview.contains("EVENT_30"))
        assertTrue(preview.contains("日志过长"))
    }
}
