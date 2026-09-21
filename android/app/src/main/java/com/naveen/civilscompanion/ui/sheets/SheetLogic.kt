package com.naveen.civilscompanion.ui.sheets

import com.naveen.civilscompanion.data.records.TimeUtil
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure logic of the revision sheets and last-month mode (no Android classes). Mirrors backend/app/features/reports/plan.py. */
object SheetLogic {
    const val PER_DAY = 9
    const val LAST_MONTH_DAYS = 30
    const val WORDS_PER_SECOND = 2.5

    /**
     * Which sheets to revise on a day in last-month mode. items = (topic id, importance). Sheets are sorted by importance
     * (high first, then topic id); a window of [perDay] starts at (epochDay * perDay) modulo the number of sheets.
     * The server plan block uses the same rule, so the plan and this screen agree.
     */
    fun pickSheets(items: List<Pair<String, Double>>, epochDay: Long, perDay: Int = PER_DAY): List<String> {
        val ordered = items.sortedWith(compareBy<Pair<String, Double>>({ -it.second }, { it.first })).map { it.first }
        val n = ordered.size
        if (n == 0) return emptyList()
        if (n <= perDay) return ordered
        val start = ((epochDay * perDay) % n).toInt()
        return List(perDay) { i -> ordered[(start + i) % n] }
    }

    /** Days from [today] (YYYY-MM-DD, India) to an exam given as an ISO time (UTC). Null when there is no date or it has passed. */
    fun daysLeft(examIso: String?, today: String): Int? {
        val millis = TimeUtil.parse(examIso) ?: return null
        val exam = LocalDate.parse(TimeUtil.dateOf(millis))
        val days = ChronoUnit.DAYS.between(LocalDate.parse(today), exam).toInt()
        return if (days >= 0) days else null
    }

    /** The smallest number of days left (0 to 30) among exams, or null when no exam is that close (last-month mode is off). */
    fun lastMonthDays(daysLeft: List<Int?>): Int? = daysLeft.filterNotNull().filter { it in 0..LAST_MONTH_DAYS }.minOrNull()

    /** Splits the sheet script into short pieces for the reading voice. */
    fun sentences(text: String): List<String> =
        text.replace("\n", " ").split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** "about 3 min" for a length in seconds. */
    fun audioLabel(seconds: Int): String {
        if (seconds <= 0) return "under 1 min"
        val minutes = Math.max(1, Math.round(seconds / 60.0).toInt())
        return "about $minutes min"
    }

    fun estimateSeconds(text: String): Int = Math.round(text.split(Regex("\\s+")).count { it.isNotBlank() } / WORDS_PER_SECOND).toInt()
}
