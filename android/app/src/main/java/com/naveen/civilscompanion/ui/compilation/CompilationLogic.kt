package com.naveen.civilscompanion.ui.compilation

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Small pure helpers for the monthly compilation screens (plain Kotlin, unit tested). Months are "YYYY-MM". */
object CompilationLogic {
    private val PATTERN = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

    fun isValidMonth(month: String): Boolean = PATTERN.matches(month)

    /** "2026-08" -> "August 2026". Anything else is returned unchanged. */
    fun monthLabel(month: String): String {
        if (!isValidMonth(month)) return month
        val m = Month.of(month.substring(5).toInt())
        return m.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.substring(0, 4)
    }

    /** The month before `today` (YYYY-MM-DD): the one the server compiles on the 1st. */
    fun previousMonth(today: String): String = YearMonth.from(LocalDate.parse(today)).minusMonths(1).toString()

    fun currentMonth(today: String): String = YearMonth.from(LocalDate.parse(today)).toString()

    /** File name for a saved or shared PDF. Only safe characters. */
    fun pdfFileName(month: String): String =
        (if (isValidMonth(month)) month else "digest") + "-current-affairs.pdf"

    /** Newest month first; rows with a broken month go last. */
    fun <T> newestFirst(items: List<T>, monthOf: (T) -> String): List<T> =
        items.sortedWith(compareByDescending<T> { isValidMonth(monthOf(it)) }.thenByDescending { monthOf(it) })

    /** Rough reading time of the digest text (about 200 words a minute, at least 1). */
    fun readingMinutes(markdown: String): Int {
        val words = markdown.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        return maxOf(1, (words + 199) / 200)
    }

    /** True when there is nothing worth reading (the server writes a short "Nothing was collected" digest then). */
    fun isEmptyDigest(markdown: String): Boolean = markdown.contains("Nothing was collected")
}
