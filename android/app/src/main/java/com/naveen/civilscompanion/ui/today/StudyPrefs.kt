package com.naveen.civilscompanion.ui.today

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The study settings shared with the server (KV keys, see docs/build-guide.md section 9):
 *   study.hours          {"mon":4,...,"sun":3}
 *   study.telugu_minutes 15
 *   exam.priority        {"UPSC":1,"APPSC":1,"SI":1}
 * Reading is forgiving: anything missing or odd falls back to the default. Plain Kotlin, unit tested.
 */
object StudyPrefs {
    const val KEY_HOURS = "study.hours"
    const val KEY_TELUGU = "study.telugu_minutes"
    const val KEY_PRIORITY = "exam.priority"
    const val KEY_LIBRARY_DAY = "study.library_day"

    val DAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val DEFAULT_HOURS: Map<String, Double> =
        mapOf("mon" to 4.0, "tue" to 4.0, "wed" to 4.0, "thu" to 4.0, "fri" to 4.0, "sat" to 4.0, "sun" to 3.0)
    const val DEFAULT_TELUGU = 15
    const val MAX_HOURS = 12.0

    private fun num(e: JsonElement?): Double? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    fun parseHours(e: JsonElement?): Map<String, Double> {
        val o = e as? JsonObject ?: return DEFAULT_HOURS
        return DAY_KEYS.associateWith { key -> (num(o[key]) ?: DEFAULT_HOURS.getValue(key)).coerceIn(0.0, MAX_HOURS) }
    }

    fun hoursJson(h: Map<String, Double>): JsonObject =
        JsonObject(DAY_KEYS.associateWith { JsonPrimitive(h[it] ?: DEFAULT_HOURS.getValue(it)) })

    /** Hours planned for a date (Monday = index 0). */
    fun hoursFor(date: LocalDate, h: Map<String, Double>): Double =
        h[DAY_KEYS[date.dayOfWeek.value - 1]] ?: DEFAULT_HOURS.getValue(DAY_KEYS[date.dayOfWeek.value - 1])

    fun weekHours(h: Map<String, Double>): Double = DAY_KEYS.sumOf { h[it] ?: 0.0 }

    fun parseTelugu(e: JsonElement?): Int {
        val p = (e as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return DEFAULT_TELUGU
        return (p.intOrNull ?: p.doubleOrNull?.toInt() ?: DEFAULT_TELUGU).coerceIn(0, 120)
    }

    fun parsePriority(e: JsonElement?): Map<String, Double> {
        val o = e as? JsonObject
        return mapOf(
            "UPSC" to (num(o?.get("UPSC")) ?: 1.0).coerceAtLeast(0.0),
            "APPSC" to (num(o?.get("APPSC")) ?: 1.0).coerceAtLeast(0.0),
            "SI" to (num(o?.get("SI")) ?: 1.0).coerceAtLeast(0.0),
        )
    }

    fun priorityJson(p: Map<String, Double>): JsonObject =
        JsonObject(
            mapOf(
                "UPSC" to JsonPrimitive(p["UPSC"] ?: 1.0),
                "APPSC" to JsonPrimitive(p["APPSC"] ?: 1.0),
                "SI" to JsonPrimitive(p["SI"] ?: 1.0),
            ),
        )

    /** 0 = all equal, 1 = more on UPSC, 2 = more on APPSC, 3 = more on SI (the single strictly largest weight). */
    fun priorityMode(p: Map<String, Double>): Int {
        val u = p["UPSC"] ?: 1.0
        val a = p["APPSC"] ?: 1.0
        val s = p["SI"] ?: 1.0
        return when {
            u > a && u > s -> 1
            a > u && a > s -> 2
            s > u && s > a -> 3
            else -> 0
        }
    }

    fun priorityFor(mode: Int): Map<String, Double> = mapOf(
        "UPSC" to (if (mode == 1) 2.0 else 1.0),
        "APPSC" to (if (mode == 2) 2.0 else 1.0),
        "SI" to (if (mode == 3) 2.0 else 1.0),
    )
}

/** Exam dates are stored as ISO text ("2027-02-14T00:00:00Z"); only the first ten characters (the day) matter. */
object ExamDates {
    fun parseDay(text: String?): LocalDate? =
        if (text == null || text.length < 10) null else runCatching { LocalDate.parse(text.substring(0, 10)) }.getOrNull()

    /** Days from today to the exam; null when the date is not announced; negative when it has passed. */
    fun daysLeft(text: String?, today: LocalDate): Int? =
        parseDay(text)?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    fun countdownText(text: String?, today: LocalDate): String {
        val d = daysLeft(text, today) ?: return "Date not announced"
        return when {
            d < 0 -> "Done"
            d == 0 -> "Today"
            d == 1 -> "Tomorrow"
            d < 60 -> "$d days to go"
            else -> "${d / 30} months to go"
        }
    }

    /** What the tablet saves in Exam.date for a day typed by the owner. */
    fun toStored(day: LocalDate): String = "${day}T00:00:00Z"
}
