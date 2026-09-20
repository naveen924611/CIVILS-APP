package com.naveen.civilscompanion

import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BriefTimesTest {
    // Sunday 20 Sep 2026, 08:30 in India
    private val now = ZonedDateTime.parse("2026-09-20T08:30:00+05:30[Asia/Kolkata]")

    @Test
    fun morningBriefIsTomorrowWhenTodaysTimeHasPassed() {
        val next = BriefTimes.nextOccurrence(BriefSlotDto("morning", "07:00"), now)!!
        assertEquals("2026-09-21T07:00+05:30[Asia/Kolkata]", next.toString())
    }

    @Test
    fun eveningBriefIsLaterToday() {
        val next = BriefTimes.nextOccurrence(BriefSlotDto("evening", "19:00"), now)!!
        assertEquals("2026-09-20T19:00+05:30[Asia/Kolkata]", next.toString())
    }

    @Test
    fun weekdaysAreCountedFromMonday() {
        val monday = BriefSlotDto("morning", "07:00", days = listOf(0))
        assertEquals("2026-09-21T07:00+05:30[Asia/Kolkata]", BriefTimes.nextOccurrence(monday, now).toString())
        val sunday = BriefSlotDto("morning", "07:00", days = listOf(6))
        assertEquals("2026-09-27T07:00+05:30[Asia/Kolkata]", BriefTimes.nextOccurrence(sunday, now).toString())
    }

    @Test
    fun switchedOffOrEmptySlotsHaveNoAlarm() {
        assertNull(BriefTimes.nextOccurrence(BriefSlotDto("morning", "07:00", enabled = false), now))
        assertNull(BriefTimes.nextOccurrence(BriefSlotDto("morning", "07:00", days = emptyList()), now))
        assertNull(BriefTimes.nextOccurrence(BriefSlotDto("morning", "nonsense"), now))
    }

    @Test
    fun timeShiftWrapsAroundMidnight() {
        assertEquals("00:05", BriefTimes.shift("23:50", 15))
        assertEquals("23:55", BriefTimes.shift("00:05", -10))
        assertEquals("08:00", BriefTimes.shift("07:00", 60))
    }

    @Test
    fun timesAreShownTheWayPeopleSayThem() {
        assertEquals("12:00 AM", BriefTimes.display("00:00"))
        assertEquals("12:30 PM", BriefTimes.display("12:30"))
        assertEquals("7:00 AM", BriefTimes.display("07:00"))
        assertEquals("7:00 PM", BriefTimes.display("19:00"))
    }

    @Test
    fun parseRejectsBadTimes() {
        assertNull(BriefTimes.parseTime("25:00"))
        assertNull(BriefTimes.parseTime("7"))
        assertEquals(6 to 30, BriefTimes.parseTime("06:30"))
    }
}
