package com.naveen.civilscompanion.notify

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayNotifyLogicTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun summaryDefaultsAndParsing() {
        assertEquals(DayNotifyLogic.DaySummary(true, "21:00"), DayNotifyLogic.parseSummary(null))
        val o = JsonObject(mapOf("enabled" to JsonPrimitive(false), "time" to JsonPrimitive("22:30")))
        assertEquals(DayNotifyLogic.DaySummary(false, "22:30"), DayNotifyLogic.parseSummary(o))
        val bad = JsonObject(mapOf("time" to JsonPrimitive("25:99")))
        assertEquals("21:00", DayNotifyLogic.parseSummary(bad).time)
        assertEquals(o, DayNotifyLogic.summaryJson(DayNotifyLogic.DaySummary(false, "22:30")))
    }

    @Test fun quietParsingRoundTrip() {
        val q = DayNotifyLogic.Quiet(true, "22:00", "05:30")
        assertEquals(q, DayNotifyLogic.parseQuiet(DayNotifyLogic.quietJson(q)))
        assertEquals(DayNotifyLogic.Quiet(), DayNotifyLogic.parseQuiet(JsonPrimitive("nonsense")))
    }

    @Test fun boolParsing() {
        assertTrue(DayNotifyLogic.parseBool(null, true))
        assertFalse(DayNotifyLogic.parseBool(JsonPrimitive(false), true))
        assertTrue(DayNotifyLogic.parseBool(JsonPrimitive("maybe"), true))
    }

    @Test fun quietHoursWrapAroundMidnight() {
        val q = DayNotifyLogic.Quiet(true, "23:00", "06:00")
        assertTrue(DayNotifyLogic.isQuiet(q, 23 * 60))
        assertTrue(DayNotifyLogic.isQuiet(q, 0))
        assertTrue(DayNotifyLogic.isQuiet(q, 5 * 60 + 59))
        assertFalse(DayNotifyLogic.isQuiet(q, 6 * 60))
        assertFalse(DayNotifyLogic.isQuiet(q, 12 * 60))
        assertFalse(DayNotifyLogic.isQuiet(q.copy(enabled = false), 0))
    }

    @Test fun quietHoursInsideOneDay() {
        val q = DayNotifyLogic.Quiet(true, "13:00", "15:00")
        assertTrue(DayNotifyLogic.isQuiet(q, 14 * 60))
        assertFalse(DayNotifyLogic.isQuiet(q, 15 * 60))
        assertFalse(DayNotifyLogic.isQuiet(DayNotifyLogic.Quiet(true, "08:00", "08:00"), 8 * 60))
    }

    @Test fun minuteOfDayUsesTheZone() {
        assertEquals(17 * 60 + 5, DayNotifyLogic.minuteOfDay(at(2026, 9, 21, 17, 5), zone))
    }

    @Test fun nextDailyIsTodayOrTomorrow() {
        assertEquals(at(2026, 9, 21, 18, 0), DayNotifyLogic.nextDaily("18:00", at(2026, 9, 21, 17, 0), zone))
        assertEquals(at(2026, 9, 22, 18, 0), DayNotifyLogic.nextDaily("18:00", at(2026, 9, 21, 18, 0), zone))
        assertEquals(at(2026, 9, 22, 0, 30), DayNotifyLogic.nextDaily("00:30", at(2026, 9, 21, 23, 0), zone))
        assertNull(DayNotifyLogic.nextDaily("soon", 0L, zone))
    }

    @Test fun nextSundayEvening() {
        // 21 Sep 2026 is a Monday, 20 Sep and 27 Sep are Sundays.
        assertEquals(at(2026, 9, 27, 19, 30), DayNotifyLogic.nextSunday("19:30", at(2026, 9, 21, 10, 0), zone))
        assertEquals(at(2026, 9, 20, 19, 30), DayNotifyLogic.nextSunday("19:30", at(2026, 9, 20, 10, 0), zone))
        assertEquals(at(2026, 9, 27, 19, 30), DayNotifyLogic.nextSunday("19:30", at(2026, 9, 20, 20, 0), zone))
        assertNotNull(DayNotifyLogic.nextSunday("19:30", 0L, zone))
    }

    @Test fun revisionMessage() {
        assertEquals(30, DayNotifyLogic.revisionMinutes(38))
        assertEquals(5, DayNotifyLogic.revisionMinutes(1))
        assertEquals(5, DayNotifyLogic.revisionMinutes(0))
        assertEquals("38 revision cards due · about 30 min", DayNotifyLogic.revisionText(38))
        assertEquals("1 revision card due · about 5 min", DayNotifyLogic.revisionText(1))
    }

    @Test fun summaryMessage() {
        assertEquals("3 h 10 min", DayNotifyLogic.minutesText(190))
        assertEquals("2 h", DayNotifyLogic.minutesText(120))
        assertEquals("45 min", DayNotifyLogic.minutesText(45))
        assertEquals("0 min", DayNotifyLogic.minutesText(-5))
        assertEquals(
            "You finished 4 of 6 tasks (3 h 10 min of 4 h). Tomorrow's plan is ready.",
            DayNotifyLogic.summaryText(6, 4, 240, 190, true),
        )
        assertEquals(
            "No plan was made for today. Tomorrow's plan will be made when you are online.",
            DayNotifyLogic.summaryText(0, 0, 0, 0, false),
        )
    }

    @Test fun timeHelpers() {
        assertEquals("00:30", DayNotifyLogic.shift("23:30", 60))
        assertEquals("23:45", DayNotifyLogic.shift("00:15", -30))
        assertEquals("9:00 PM", DayNotifyLogic.display("21:00"))
        assertEquals("12:05 AM", DayNotifyLogic.display("00:05"))
        assertEquals("12:00 PM", DayNotifyLogic.display("12:00"))
        assertEquals(425, DayNotifyLogic.minutesOf("7:05"))
        assertNull(DayNotifyLogic.minutesOf("24:00"))
    }
}
