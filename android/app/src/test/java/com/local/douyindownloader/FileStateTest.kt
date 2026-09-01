package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class FileStateTest {
    @Test
    fun distinguishesCompletePartialMissingAndUnknownOutputs() {
        assertEquals(FileState.AVAILABLE, fileStateForExistence(listOf(true, true)))
        assertEquals(FileState.PARTIAL, fileStateForExistence(listOf(true, false)))
        assertEquals(FileState.MISSING, fileStateForExistence(listOf(false, false)))
        assertEquals(FileState.UNKNOWN, fileStateForExistence(emptyList()))
    }
}
