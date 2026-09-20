package com.naveen.civilscompanion.ui.today

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One block of a day's plan: {id, kind, start "HH:MM", minutes, title, detail, topic_id, ref}. `ref` is a tablet route
 * (Routes constants, e.g. "revise/session", "notes/<id>", "briefs") that the Start button opens. Plain Kotlin, unit tested.
 */
data class PlanBlock(
    val id: String,
    val kind: String,
    val start: String,
    val minutes: Int,
    val title: String,
    val detail: String = "",
    val topicId: String? = null,
    val ref: String? = null,
)

object PlanBlocks {
    const val DONE = "done"
    const val SKIPPED = "skipped"

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    fun parse(o: JsonObject): PlanBlock? {
        val id = o.str("id") ?: return null
        return PlanBlock(
            id = id,
            kind = o.str("kind") ?: "other",
            start = o.str("start") ?: "00:00",
            minutes = o.int("minutes") ?: 0,
            title = o.str("title") ?: "",
            detail = o.str("detail") ?: "",
            topicId = o.str("topic_id"),
            ref = o.str("ref"),
        )
    }

    fun parseAll(list: List<JsonObject>): List<PlanBlock> =
        list.mapNotNull { parse(it) }.sortedWith(compareBy<PlanBlock>({ it.start }, { it.id }))

    fun statusOf(completion: Map<String, String>, id: String): String = completion[id] ?: "open"

    fun totalMinutes(blocks: List<PlanBlock>): Int = blocks.sumOf { it.minutes }

    /** Label on the Start button of a block. */
    fun startLabel(kind: String): String = when (kind) {
        "brief" -> "Listen"
        "revision", "review" -> "Revise"
        "practice", "telugu" -> "Practice"
        "answer" -> "Write"
        "mock" -> "Start test"
        "library", "sheet" -> "Open"
        else -> "Start"
    }

    fun toMinutes(text: String?, default: Int): Int {
        val parts = text?.split(":") ?: return default
        if (parts.size != 2) return default
        val h = parts[0].trim().toIntOrNull() ?: return default
        val m = parts[1].trim().toIntOrNull() ?: return default
        return h * 60 + m
    }

    fun hhmm(minutes: Int): String {
        val v = min(max(minutes, 0), 23 * 60 + 59)
        return "%02d:%02d".format(Locale.ROOT, v / 60, v % 60)
    }

    /** "07:00" -> "7:00 AM". */
    fun displayTime(text: String): String {
        val m = toMinutes(text, -1)
        if (m < 0) return text
        val h = m / 60
        val suffix = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(Locale.ROOT, h12, m % 60, suffix)
    }

    private fun round5(x: Double): Int = (x / 5.0).roundToInt() * 5

    /**
     * The plan the tablet makes for itself when the server has not sent one yet (for example the first day, offline):
     * briefs at their times, revision at the slot, Telugu, core study and practice in between.
     * morning / evening are the brief times ("07:00") or null when that brief is off.
     */
    fun fallback(
        date: String,
        hours: Double,
        morning: String?,
        evening: String?,
        revisionSlot: String,
        dueCards: Int,
        teluguMinutes: Int,
        minutesPerCard: Double = 0.7,
    ): List<PlanBlock> {
        if (hours <= 0.0) return emptyList()
        val total = (hours * 60).roundToInt()
        val briefM = if (morning != null) round5(total * 0.12) else 0
        val briefE = if (evening != null) round5(total * 0.06) else 0
        val rev = if (dueCards > 0) round5(min(max(dueCards * minutesPerCard, 10.0), 0.4 * total)) else 0
        val practice = round5(total * 0.13)
        var core = total - briefM - briefE - rev - practice
        val telugu = if (teluguMinutes > 0) min(teluguMinutes, max(core - 15, 0)) else 0
        core -= telugu
        core = max(core, 0)

        val blocks = ArrayList<PlanBlock>()
        val morningStart = toMinutes(morning, 7 * 60)
        val revStart = toMinutes(revisionSlot, 18 * 60)
        if (briefM > 0) blocks.add(PlanBlock("fb-$date-morning-brief", "brief", hhmm(morningStart), briefM, "Morning brief", "Today's current affairs, listen or read", null, "briefs"))
        if (briefE > 0) blocks.add(PlanBlock("fb-$date-evening-brief", "brief", hhmm(toMinutes(evening, 19 * 60)), briefE, "Evening brief", "Recap of the day and a short quiz", null, "briefs"))
        if (rev > 0) blocks.add(PlanBlock("fb-$date-revision", "revision", hhmm(revStart), rev, "Revision", "$dueCards cards due", null, "revise/session"))

        var cursor = if (briefM > 0) morningStart + briefM else max(morningStart, 7 * 60 + 30)
        fun place(id: String, kind: String, minutes: Int, title: String, detail: String, ref: String?) {
            if (minutes <= 0) return
            if (rev > 0 && cursor < revStart + rev && cursor + minutes > revStart) cursor = revStart + rev
            blocks.add(PlanBlock("fb-$date-$id", kind, hhmm(cursor), minutes, title, detail, null, ref))
            cursor += minutes
        }
        place("core", "study", if (core >= 15) core else 0, "Core study", "Pick a topic in the syllabus map, then read or listen", "syllabus")
        place("telugu", "telugu", telugu, "Telugu practice", "Vocabulary, reading and one short writing task", "telugu")
        place("practice", "practice", if (practice >= 15) practice else 0, "Practice", "Past-paper style questions", "tests")
        return blocks.sortedWith(compareBy<PlanBlock>({ it.start }, { it.id }))
    }

    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)
    private val shortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "Sunday, 20 September". */
    fun longDate(date: LocalDate): String = dayFormat.format(date)

    /** "Sun 20 Sep". */
    fun shortDate(date: LocalDate): String = shortDayFormat.format(date)

    fun greeting(hour: Int): String = when {
        hour < 5 -> "Good night"
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }

    /** "3 h 30 min", "45 min", "4 h". */
    fun hoursText(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }
}
