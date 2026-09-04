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
}
