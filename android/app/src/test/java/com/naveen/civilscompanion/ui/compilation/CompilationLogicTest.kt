package com.naveen.civilscompanion.ui.compilation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilationLogicTest {
    @Test
    fun monthsAreValidatedAndNamed() {
        assertTrue(CompilationLogic.isValidMonth("2026-01"))
        assertFalse(CompilationLogic.isValidMonth("2026-13"))
        assertFalse(CompilationLogic.isValidMonth("2026-8"))
        assertFalse(CompilationLogic.isValidMonth(""))
        assertEquals("August 2026", CompilationLogic.monthLabel("2026-08"))
        assertEquals("December 2025", CompilationLogic.monthLabel("2025-12"))
        assertEquals("bad", CompilationLogic.monthLabel("bad"))
    }

    @Test
    fun previousAndCurrentMonth() {
        assertEquals("2026-08", CompilationLogic.previousMonth("2026-09-21"))
        assertEquals("2025-12", CompilationLogic.previousMonth("2026-01-05"))
        assertEquals("2026-09", CompilationLogic.currentMonth("2026-09-21"))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("2026-08-current-affairs.pdf", CompilationLogic.pdfFileName("2026-08"))
        assertEquals("digest-current-affairs.pdf", CompilationLogic.pdfFileName("../x"))
    }

    @Test
    fun newestMonthFirstAndBrokenLast() {
        val sorted = CompilationLogic.newestFirst(listOf("2026-07", "bad", "2026-09", "2025-12")) { it }
        assertEquals(listOf("2026-09", "2026-07", "2025-12", "bad"), sorted)
    }

    @Test
    fun readingTimeAndEmptyDigest() {
        assertEquals(1, CompilationLogic.readingMinutes(""))
        assertEquals(1, CompilationLogic.readingMinutes("one two three"))
        assertEquals(1, CompilationLogic.readingMinutes(List(200) { "w" }.joinToString(" ")))
        assertEquals(3, CompilationLogic.readingMinutes(List(401) { "w" }.joinToString(" ")))
        assertTrue(CompilationLogic.isEmptyDigest("# T\n\nNothing was collected for this month yet.\n"))
        assertFalse(CompilationLogic.isEmptyDigest("# T\n\n- story"))
    }
}
