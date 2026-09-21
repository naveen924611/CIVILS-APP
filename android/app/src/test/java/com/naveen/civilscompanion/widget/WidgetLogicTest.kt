package com.naveen.civilscompanion.widget

import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.today.PlanBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLogicTest {
    private val blocks = listOf(
        PlanBlock("a", "brief", "07:00", 20, "Morning brief", ref = "briefs"),
        PlanBlock("b", "study", "09:00", 90, "Polity: Parliament", ref = "notes/t1"),
        PlanBlock("c", "revision", "18:00", 30, "Revision"),
    )

    @Test fun nextTaskIsTheFirstOpenBlock() {
        val m = WidgetLogic.build(blocks, mapOf("a" to "done"), cardsDue = 38, studiedMinutes = 135, nextBrief = "Evening brief 7:00 PM")
        assertTrue(m.hasPlan)
        assertEquals("9:00 AM", m.nextTime)
        assertEquals("Polity: Parliament", m.nextTitle)
        assertEquals("notes/t1", m.nextRoute)
        assertEquals(2, m.tasksLeft)
        assertEquals(3, m.tasksTotal)
        assertEquals(38, m.cardsDue)
        assertEquals("2 h 15 min", m.studiedText)
        assertEquals("2 of 3 tasks left", WidgetLogic.planLine(m))
    }

    @Test fun skippedBlocksAreNotNext() {
        val m = WidgetLogic.build(blocks, mapOf("a" to "done", "b" to "skipped"), 0, 0, "")
        assertEquals("Revision", m.nextTitle)
        assertEquals(Routes.TODAY, m.nextRoute) // no route on that block: open Today
        assertEquals(1, m.tasksLeft)
    }

    @Test fun everythingDone() {
        val m = WidgetLogic.build(blocks, mapOf("a" to "done", "b" to "done", "c" to "done"), 5, 45, "")
        assertEquals(0, m.tasksLeft)
        assertEquals("", m.nextTitle)
        assertEquals("All 3 tasks done today", WidgetLogic.planLine(m))
        assertEquals("45 min", m.studiedText)
    }

    @Test fun noPlanYet() {
        val m = WidgetLogic.build(emptyList(), emptyMap(), -4, -1, "")
        assertFalse(m.hasPlan)
        assertEquals(0, m.cardsDue)
        assertEquals("0 min", m.studiedText)
        assertEquals("No plan yet. Open the app to sync.", WidgetLogic.planLine(m))
    }
}
