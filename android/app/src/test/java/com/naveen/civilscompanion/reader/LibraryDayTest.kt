package com.naveen.civilscompanion.reader

import com.naveen.civilscompanion.ui.library.LibraryDay
import com.naveen.civilscompanion.ui.library.libraryDayLabel
import com.naveen.civilscompanion.ui.library.nextWeekday
import com.naveen.civilscompanion.ui.library.parseLibraryDate
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryDayTest {
    @Test
    fun nextWeekdayIsNeverToday() {
        val sunday = LocalDate.of(2026, 9, 20)
        assertEquals(LocalDate.of(2026, 9, 26), nextWeekday(sunday, DayOfWeek.SATURDAY))
        assertEquals(LocalDate.of(2026, 9, 27), nextWeekday(sunday, DayOfWeek.SUNDAY))
    }

    @Test
    fun datesAreParsedAndLabelled() {
        assertEquals(LocalDate.of(2026, 10, 3), parseLibraryDate(" 2026-10-03 "))
        assertNull(parseLibraryDate("3 Oct"))
        assertNull(parseLibraryDate("2026-13-40"))
        assertEquals("Sat, 3 Oct", libraryDayLabel("2026-10-03"))
        assertEquals("Not set", libraryDayLabel(null))
        assertEquals("Not set", libraryDayLabel("soon"))
    }

    @Test
    fun settingReadsTheSharedJsonShape() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(LibraryDay(false, null), json.decodeFromString(LibraryDay.serializer(), """{"enabled":false,"date":null}"""))
        assertEquals(LibraryDay(true, "2026-10-03"), json.decodeFromString(LibraryDay.serializer(), """{"enabled":true,"date":"2026-10-03"}"""))
    }
}
