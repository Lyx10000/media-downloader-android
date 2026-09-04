package com.local.douyindownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskSelectionTest {
    @Test
    fun togglesSelectedTaskIds() {
        assertEquals(setOf("a", "b"), toggleTaskSelection(setOf("a"), "b"))
        assertEquals(setOf("b"), toggleTaskSelection(setOf("a", "b"), "a"))
    }

    @Test
    fun dropsIdsThatAreNoLongerVisible() {
        assertEquals(setOf("b"), reconcileTaskSelection(setOf("a", "b"), setOf("b", "c")))
    }
}
