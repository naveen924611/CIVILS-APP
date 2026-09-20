package com.naveen.civilscompanion.srs

import java.time.Instant
import kotlin.math.min

/*
 * Topic strength and automatic status upgrades, from the memory state of the topic's cards. A plain Kotlin port of
 * backend/app/srs/strength.py (checked against the "strength_cases" of data/fsrs_vectors.json). Keep both identical.
 *
 * memory of one card = retrievability(now) * min(1, stability / 21); a card never reviewed counts 0.
 * strength = average memory of all cards of the topic; coverage = share of cards reviewed at least once;
 * repeatShare = share reviewed at least twice. Status only moves UP:
 *   strong       coverage >= 0.8 and strength >= 0.70
 *   revised      coverage >= 0.6 and repeatShare >= 0.5 and strength >= 0.30
 *   studied      coverage >= 0.6
 *   in_progress  coverage > 0
 */
data class TopicMemory(val strength: Double, val coverage: Double, val repeatShare: Double, val cards: Int)

object Strength {
    const val STABILITY_DURABLE_DAYS = 21.0
    val STATUS_ORDER = listOf("not_started", "in_progress", "studied", "revised", "strong")

    fun cardMemory(engine: Fsrs, state: CardMemory?, now: Instant): Double {
        if (state == null || state.isNew) return 0.0
        val stability = state.stability ?: return 0.0
        return engine.retrievability(state, now) * min(1.0, stability / STABILITY_DURABLE_DAYS)
    }

    /** null when the topic has no cards. */
    fun topicMemory(engine: Fsrs, states: List<CardMemory?>, now: Instant): TopicMemory? {
        val n = states.size
        if (n == 0) return null
        val total = states.sumOf { cardMemory(engine, it, now) }
        val reviewed = states.count { it != null && !it.isNew }
        val repeated = states.count { it != null && !it.isNew && it.reps >= 2 }
        return TopicMemory(total / n, reviewed.toDouble() / n, repeated.toDouble() / n, n)
    }

    /** The status after applying the upgrade rules to `current` (never lower than `current`). */
    fun statusFor(current: String, mem: TopicMemory): String {
        val wanted = when {
            mem.coverage >= 0.8 && mem.strength >= 0.70 -> "strong"
            mem.coverage >= 0.6 && mem.repeatShare >= 0.5 && mem.strength >= 0.30 -> "revised"
            mem.coverage >= 0.6 -> "studied"
            mem.coverage > 0 -> "in_progress"
            else -> "not_started"
        }
        val cur = STATUS_ORDER.indexOf(current).coerceAtLeast(0)
        return STATUS_ORDER[maxOf(cur, STATUS_ORDER.indexOf(wanted))]
    }
}
