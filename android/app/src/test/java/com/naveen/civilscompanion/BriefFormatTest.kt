package com.naveen.civilscompanion

import com.naveen.civilscompanion.ui.briefs.BriefFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefFormatTest {
    @Test
    fun minutesRoundToWholeNumbersAndNeverShowZero() {
        assertEquals("3 min", BriefFormat.minutes(150))
        assertEquals("1 min", BriefFormat.minutes(20))
        assertEquals("", BriefFormat.minutes(0))
        assertEquals("", BriefFormat.minutes(null))
    }

    @Test
    fun clockShowsMinutesAndSeconds() {
        assertEquals("1:42", BriefFormat.clock(102_000))
        assertEquals("0:00", BriefFormat.clock(-5))
    }

    @Test
    fun speedLabels() {
        assertEquals("1×", BriefFormat.speed(1f))
        assertEquals("1.25×", BriefFormat.speed(1.25f))
        assertEquals("1.5×", BriefFormat.speed(1.5f))
    }

    @Test
    fun datesUseIndiaTime() {
        // 2026-09-21 01:30 UTC is Monday 7:00 AM in India
        val ms = java.time.Instant.parse("2026-09-21T01:30:00Z").toEpochMilli()
        assertEquals("Mon 21 Sep", BriefFormat.dayText(ms))
        assertEquals("7:00 AM", BriefFormat.timeText(ms))
    }

    @Test
    fun examLabelsLoseTheirPrefix() {
        assertEquals("GS2", BriefFormat.shortLabel("UPSC GS2"))
        assertEquals("Economy", BriefFormat.shortLabel("APPSC Economy"))
    }
}
