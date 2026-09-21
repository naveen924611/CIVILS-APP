package com.naveen.civilscompanion.notify

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Plain Kotlin (no Android) for the day notifications: settings reading, quiet hours, next alarm time and message text.
 * Setting keys (shared with the server, docs/build-guide.md 9.1):
 *   notify.day_summary   {"enabled":true,"time":"21:00"}
 *   notify.revision      true
 *   notify.weekly_report true
 *   notify.quiet_hours   {"enabled":true,"start":"23:00","end":"06:00"}
 */
object DayNotifyLogic {
    const val KEY_SUMMARY = "notify.day_summary"
    const val KEY_REVISION = "notify.revision"
    const val KEY_WEEKLY = "notify.weekly_report"
    const val KEY_QUIET = "notify.quiet_hours"
    const val KEY_SLOT = "revision.slot_time"
    const val KEY_MAX_CARDS = "revision.max_cards"
    const val WEEKLY_TIME = "19:30"
    const val MINUTES_PER_CARD = 0.7

    data class DaySummary(val enabled: Boolean = true, val time: String = "21:00")
    data class Quiet(val enabled: Boolean = true, val start: String = "23:00", val end: String = "06:00")

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun flag(e: JsonElement?): Boolean? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    fun parseBool(e: JsonElement?, default: Boolean): Boolean = flag(e) ?: default

    fun parseSummary(e: JsonElement?): DaySummary {
        val o = obj(e) ?: return DaySummary()
        val time = text(o["time"])?.takeIf { minutesOf(it) != null } ?: "21:00"
        return DaySummary(flag(o["enabled"]) ?: true, time)
    }

    fun summaryJson(s: DaySummary): JsonObject =
        JsonObject(mapOf("enabled" to JsonPrimitive(s.enabled), "time" to JsonPrimitive(s.time)))

    fun parseQuiet(e: JsonElement?): Quiet {
        val o = obj(e) ?: return Quiet()
        val start = text(o["start"])?.takeIf { minutesOf(it) != null } ?: "23:00"
        val end = text(o["end"])?.takeIf { minutesOf(it) != null } ?: "06:00"
        return Quiet(flag(o["enabled"]) ?: true, start, end)
    }

    fun quietJson(q: Quiet): JsonObject = JsonObject(
        mapOf("enabled" to JsonPrimitive(q.enabled), "start" to JsonPrimitive(q.start), "end" to JsonPrimitive(q.end)),
    )

    /** "HH:MM" to minutes after midnight, or null when it is not a time. */
    fun minutesOf(time: String): Int? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun clock(minutes: Int): String {
        val v = ((minutes % 1440) + 1440) % 1440
        return "%02d:%02d".format(v / 60, v % 60)
    }

    /** "21:00" to "9:00 PM". */
    fun display(time: String): String {
        val m = minutesOf(time) ?: return time
        val h = m / 60
        val suffix = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m % 60, suffix)
    }

    /** Adds minutes to a "HH:MM" time, wrapping around midnight. */
    fun shift(time: String, delta: Int): String = clock((minutesOf(time) ?: 0) + delta)

    /** True when [minuteOfDay] is inside the quiet hours (the range may wrap around midnight). */
    fun isQuiet(q: Quiet, minuteOfDay: Int): Boolean {
        if (!q.enabled) return false
        val start = minutesOf(q.start) ?: return false
        val end = minutesOf(q.end) ?: return false
        if (start == end) return false
        return if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
    }

    fun minuteOfDay(nowMillis: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return t.hour * 60 + t.minute
    }

    /** The next moment (epoch millis) that the wall clock in [zone] shows [time], strictly after [nowMillis]. */
    fun nextDaily(time: String, nowMillis: Long, zone: ZoneId): Long? {
        val m = minutesOf(time) ?: return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var candidate = now.toLocalDate().atTime(m / 60, m % 60).atZone(zone)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    /** The next Sunday at [time], strictly after [nowMillis]. */
    fun nextSunday(time: String, nowMillis: Long, zone: ZoneId): Long? {
        val m = minutesOf(time) ?: return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        var candidate: ZonedDateTime = now.toLocalDate().atTime(m / 60, m % 60).atZone(zone)
        while (candidate.dayOfWeek != DayOfWeek.SUNDAY || !candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    /** About how long [cards] cards take, rounded up to 5 minutes (at least 5). */
    fun revisionMinutes(cards: Int): Int {
        val raw = ceil(cards * MINUTES_PER_CARD / 5.0).toInt() * 5
        return raw.coerceAtLeast(5)
    }

    fun revisionText(due: Int): String =
        "$due revision card${if (due == 1) "" else "s"} due · about ${revisionMinutes(due)} min"

    /** "3 h 10 min", "45 min", "0 min". */
    fun minutesText(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m >= 60 && m % 60 == 0 -> "${m / 60} h"
            m >= 60 -> "${m / 60} h ${m % 60} min"
            else -> "$m min"
        }
    }

    /** Body of the evening day summary. */
    fun summaryText(planned: Int, done: Int, plannedMinutes: Int, doneMinutes: Int, tomorrowReady: Boolean): String {
        val tomorrow = if (tomorrowReady) "Tomorrow's plan is ready." else "Tomorrow's plan will be made when you are online."
        if (planned == 0) return "No plan was made for today. $tomorrow"
        return "You finished $done of $planned tasks (${minutesText(doneMinutes)} of ${minutesText(plannedMinutes)}). $tomorrow"
    }
}
