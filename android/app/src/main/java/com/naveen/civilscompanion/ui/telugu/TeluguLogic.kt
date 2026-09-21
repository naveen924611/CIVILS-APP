package com.naveen.civilscompanion.ui.telugu

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One Telugu item as the planner sees it (plain data, no Android). */
data class PItem(val id: String, val kind: String, val position: Int)

/** One finished practice: which item, its score (0 to 1, null = not scored yet), the study day and the moment. */
data class PDone(val itemId: String, val score: Double?, val day: String, val at: Long)

data class KindStat(val items: Int = 0, val practised: Int = 0, val avgScore: Double? = null)

data class DayCount(val day: String, val count: Int)

data class TeluguStats(
    val streak: Int = 0,
    val totalDone: Int = 0,
    val kinds: Map<String, KindStat> = emptyMap(),
    val recent: List<DayCount> = emptyList(),
)

/**
 * The daily Telugu set and progress numbers. Plain Kotlin, unit tested.
 * These rules are the SAME as backend/app/features/telugu/practice.py (keep them in step).
 */
object TeluguPlan {
    const val KEY_MINUTES = "study.telugu_minutes"
    const val DEFAULT_MINUTES = 15
    const val NEEDS_WORK_BELOW = 0.6
    val KINDS = listOf("vocab", "passage", "translation", "template")

    fun kindLabel(kind: String): String = when (kind) {
        "vocab" -> "Words"
        "passage" -> "Reading"
        "translation" -> "Translation"
        "template" -> "Letters and essays"
        else -> kind
    }

    /** The reserved minutes from the KV value (a number). Anything odd gives 15. */
    fun parseMinutes(e: JsonElement?): Int {
        val p = (e as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return DEFAULT_MINUTES
        return (p.intOrNull ?: p.doubleOrNull?.toInt() ?: DEFAULT_MINUTES).coerceIn(0, 120)
    }

    /** weekday: Monday = 0 ... Sunday = 6. */
    fun quotas(minutes: Int, weekday: Int): Map<String, Int> {
        val m = minutes.coerceAtLeast(0)
        if (m <= 0) return KINDS.associateWith { 0 }
        return mapOf(
            "vocab" to maxOf(4, (m + 1) / 2),
            "passage" to if (m >= 10) 1 else 0,
            "translation" to if (m >= 20) 2 else if (m >= 10) 1 else 0,
            "template" to if (m >= 15 && (weekday == 2 || weekday == 5)) 1 else 0,
        )
    }

    /** Study day (India) of an ISO time such as 2026-09-21T04:00:00Z. Null when the text is not a time. */
    fun dayOf(iso: String?, zone: ZoneId): String? =
        iso?.let { runCatching { Instant.parse(it).atZone(zone).toLocalDate().toString() }.getOrNull() }

    fun epochOf(iso: String?): Long = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private val byPosition = compareBy<PItem>({ it.position }, { it.id })

    fun latestByItem(history: List<PDone>): Map<String, PDone> =
        history.sortedBy { it.at }.associateBy { it.itemId }

    /** The items for `today` (YYYY-MM-DD), grouped by kind in KINDS order. Items already done today are always kept. */
    fun pickToday(items: List<PItem>, history: List<PDone>, today: String, minutes: Int): List<PItem> {
        val weekday = LocalDate.parse(today).dayOfWeek.value - 1
        val want = quotas(minutes, weekday)
        val latest = latestByItem(history)
        val doneToday = history.filter { it.day == today }.map { it.itemId }.toSet()
        val out = ArrayList<PItem>()
        for (kind in KINDS) {
            val pool = items.filter { it.kind == kind }.sortedWith(byPosition)
            val chosen = ArrayList(pool.filter { it.id in doneToday })
            val room = (want[kind] ?: 0) - chosen.size
            if (room > 0) {
                val rest = pool.filter { it.id !in doneToday }
                val needs = rest
                    .filter { item -> latest[item.id]?.let { (it.score ?: 0.0) < NEEDS_WORK_BELOW } == true }
                    .sortedWith(compareBy<PItem>({ latest.getValue(it.id).at }, { it.position }, { it.id }))
                val take = ArrayList(needs.take(room / 2))
                val takenIds = take.map { it.id }.toSet()
                val needIds = needs.map { it.id }.toSet()
                val never = rest.filter { it.id !in latest }
                val good = rest
                    .filter { it.id in latest && it.id !in needIds }
                    .sortedWith(compareBy<PItem>({ latest.getValue(it.id).at }, { it.position }, { it.id }))
                val leftovers = needs.filter { it.id !in takenIds }
                for (item in never + good + leftovers) {
                    if (take.size >= room) break
                    take.add(item)
                }
                chosen.addAll(take)
            }
            out.addAll(chosen)
        }
        return out
    }

    /** Days in a row with practice, counting back from today (or from yesterday when today has none yet). */
    fun streak(days: Set<String>, today: String): Int {
        var day = LocalDate.parse(today)
        if (day.toString() !in days) day = day.minusDays(1)
        var count = 0
        while (day.toString() in days) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    fun stats(items: List<PItem>, history: List<PDone>, today: String, days: Int = 14): TeluguStats {
        val kindOf = items.associate { it.id to it.kind }
        val latest = latestByItem(history)
        val kinds = KINDS.associateWith { kind ->
            val practised = latest.filter { (id, _) -> kindOf[id] == kind }
            val scores = practised.values.mapNotNull { it.score }
            KindStat(
                items = items.count { it.kind == kind },
                practised = practised.size,
                avgScore = if (scores.isEmpty()) null else Math.round(scores.average() * 100.0) / 100.0,
            )
        }
        val counts = history.groupingBy { it.day }.eachCount()
        val start = LocalDate.parse(today)
        val recent = (days - 1 downTo 0).map { n ->
            val d = start.minusDays(n.toLong()).toString()
            DayCount(d, counts[d] ?: 0)
        }
        return TeluguStats(streak(counts.keys, today), history.size, kinds, recent)
    }

    /** Score of a reading passage: correct answers divided by questions (0 to 1). */
    fun passageScore(correct: Int, total: Int): Double = if (total <= 0) 0.0 else correct.toDouble() / total

    /** The server scores writing from 0 to 10; progress rows keep 0 to 1. */
    fun fractionOf10(score: Double): Double = (score / 10.0).coerceIn(0.0, 1.0)

    fun percent(score: Double?): String = if (score == null) "-" else "${Math.round(score * 100)}%"
}
