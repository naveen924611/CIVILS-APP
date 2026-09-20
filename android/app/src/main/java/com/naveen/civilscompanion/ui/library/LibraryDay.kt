package com.naveen.civilscompanion.ui.library

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.Serializable

/** Setting "study.library_day": the optional day the owner visits a library. `date` is YYYY-MM-DD or null (not set). */
@Serializable
data class LibraryDay(val enabled: Boolean = false, val date: String? = null)

const val LIBRARY_DAY_KEY = "study.library_day"

/** Parses a YYYY-MM-DD date the owner typed. Null when it is not a real date. */
fun parseLibraryDate(text: String): LocalDate? =
    try {
        LocalDate.parse(text.trim())
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }

/** The next [day] after [from] (never today), for the quick buttons "Next Saturday" and "Next Sunday". */
fun nextWeekday(from: LocalDate, day: DayOfWeek): LocalDate = from.with(TemporalAdjusters.next(day))

/** "Sat, 27 Sep" style label, or "Not set". */
fun libraryDayLabel(date: String?): String {
    val d = date?.let { parseLibraryDate(it) } ?: return "Not set"
    val dow = d.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    val month = d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$dow, ${d.dayOfMonth} $month"
}
