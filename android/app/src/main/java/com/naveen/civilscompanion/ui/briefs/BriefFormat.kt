package com.naveen.civilscompanion.ui.briefs

import com.naveen.civilscompanion.data.BriefTimes
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small text helpers for the Briefs and Alerts screens. */
object BriefFormat {
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).withZone(BriefTimes.ZONE)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(BriefTimes.ZONE)

    fun dayText(epochMs: Long): String = day.format(Instant.ofEpochMilli(epochMs))

    fun timeText(epochMs: Long): String = time.format(Instant.ofEpochMilli(epochMs))

    /** "2 min" (never 0 for real audio). */
    fun minutes(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return ""
        return "${maxOf(1, (seconds + 30) / 60)} min"
    }

    /** "1:42" */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun speed(value: Float): String {
        val text = if (value % 1f == 0f) value.toInt().toString() else value.toString().trimEnd('0')
        return "$text×"
    }

    /** "Polity" from "UPSC GS2"? We keep the exam labels as the server wrote them, dropping the "UPSC " prefix. */
    fun shortLabel(paper: String) = paper.removePrefix("UPSC ").removePrefix("APPSC ")
}
