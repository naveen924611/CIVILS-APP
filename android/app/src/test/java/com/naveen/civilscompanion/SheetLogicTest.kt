package com.naveen.civilscompanion

import com.naveen.civilscompanion.ui.sheets.SheetLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLogicTest {
    private val items = (0 until 12).map { "t$it" to (it % 3).toDouble() }

    @Test
    fun rotationMatchesTheServerRule() {
        // sorted by importance (high first) then id: t11 t2 t5 t8 | t1 t10 t4 t7 | t0 t3 t6 t9
        assertEquals(listOf("t11", "t2", "t5", "t8", "t1", "t10", "t4", "t7", "t0"), SheetLogic.pickSheets(items, 20000))
        assertEquals(listOf("t3", "t6", "t9", "t11", "t2", "t5", "t8", "t1", "t10"), SheetLogic.pickSheets(items, 20001))
    }

    @Test
    fun rotationIgnoresInputOrderAndHandlesSmallLists() {
        assertEquals(SheetLogic.pickSheets(items, 20000), SheetLogic.pickSheets(items.reversed(), 20000))
        assertEquals(listOf("b", "a"), SheetLogic.pickSheets(listOf("a" to 1.0, "b" to 5.0), 7))
        assertEquals(emptyList<String>(), SheetLogic.pickSheets(emptyList(), 7))
        assertEquals(9, SheetLogic.pickSheets(items, 123).toSet().size)
    }

    @Test
    fun daysLeftUsesIndiaDates() {
        assertEquals(15, SheetLogic.daysLeft("2026-10-05T00:00:00Z", "2026-09-20"))
        assertEquals(16, SheetLogic.daysLeft("2026-10-05T20:00:00Z", "2026-09-20")) // already the 6th in India
        assertEquals(0, SheetLogic.daysLeft("2026-09-20T02:00:00Z", "2026-09-20"))
        assertNull(SheetLogic.daysLeft("2026-09-01T00:00:00Z", "2026-09-20"))
        assertNull(SheetLogic.daysLeft(null, "2026-09-20"))
    }

    @Test
    fun lastMonthNeedsAnExamWithinThirtyDays() {
        assertEquals(12, SheetLogic.lastMonthDays(listOf(null, 45, 12, 30)))
        assertNull(SheetLogic.lastMonthDays(listOf(45, null)))
        assertEquals(0, SheetLogic.lastMonthDays(listOf(0)))
        assertEquals(30, SheetLogic.lastMonthDays(listOf(30)))
        assertNull(SheetLogic.lastMonthDays(emptyList()))
    }

    @Test
    fun scriptIsSplitIntoSentences() {
        assertEquals(
            listOf("Revision sheet.", "Key facts.", "Article 246: three lists.", "Sarkaria 1988."),
            SheetLogic.sentences("Revision sheet. Key facts.\nArticle 246: three lists. Sarkaria 1988."),
        )
        assertTrue(SheetLogic.sentences("   ").isEmpty())
    }

    @Test
    fun audioLabels() {
        assertEquals("under 1 min", SheetLogic.audioLabel(0))
        assertEquals("about 3 min", SheetLogic.audioLabel(180))
        assertEquals("about 1 min", SheetLogic.audioLabel(20))
        assertEquals(2, SheetLogic.estimateSeconds("one two three four five"))
    }
}
