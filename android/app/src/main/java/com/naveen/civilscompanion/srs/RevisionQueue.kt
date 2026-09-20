package com.naveen.civilscompanion.srs

import java.time.Instant

/*
 * Revision queue rules (spec 6.15 and 7.5): plain Kotlin port of backend/app/features/revision/queue.py. Keep both in step.
 *
 * A queue is a list of GROUPS: one per topic (cards without a topic are grouped by their `group` name, for example
 * "Current affairs"). Smart order ranks groups by
 *     priority = w1*(1 - retrievability) + w2*weakness + w3*importance + w4*exam_proximity      (w = 0.4, 0.25, 0.2, 0.15)
 */

data class QueueCard(
    val id: String,
    val topicId: String?,
    val group: String,
    val state: CardMemory?,
    val dueAt: Instant?,
)

/** Facts about a topic used for ranking; every number is 0..1 (importance is the topic's 0..10 divided by 10). */
data class TopicFacts(
    val id: String,
    val title: String,
    val subject: String = "",
    val strength: Double = 0.0,
    val importance: Double = 0.0,
    val examProximity: Double = 0.0,
)

data class RevisionConfig(
    val maxCards: Int = 80,
    val newPerDay: Int = 20,
    val weights: List<Double> = listOf(0.4, 0.25, 0.2, 0.15),
    val pinnedSubject: String? = null,
    val dailyGroup: String? = null,
    val dailyGroupCards: Int = 10,
    val sundayReview: Boolean = true,
    val minutesPerCard: Double = 0.7,
)

data class QueueGroup(
    val key: String,
    val topicId: String?,
    val title: String,
    val subject: String,
    val reason: String,
    val priority: Double,
    val cardIds: List<String>,
    val due: Int = 0,
    val newCards: Int = 0,
    val extra: Int = 0,
) {
    val count: Int get() = cardIds.size

    fun minutes(perCard: Double): Int = if (cardIds.isEmpty()) 0 else Math.max(1, Math.rint(count * perCard).toInt())
}

object RevisionQueue {
    const val REASON_WEAK = "Weak"
    const val REASON_DUE = "Due today"
    const val REASON_FADING = "Fading"
    const val REASON_BRIEFS = "From your briefs"
    const val REASON_PAPERS = "Often in past papers"
    const val REASON_SUNDAY = "Sunday review"
    const val REASON_NEW = "New cards"
    const val REASON_PINNED = "Pinned subject"

    private const val SUNDAY_EXTRA_CAP = 40
    private const val SUNDAY_RETRIEVABILITY = 0.9

    fun groupKey(card: QueueCard): String = card.topicId ?: "g:" + card.group.ifEmpty { "Cards" }

    /** Due = due time at or before the end of the study day. A never-reviewed card is due at once unless it was snoozed. */
    fun isDue(card: QueueCard, endOfDay: Instant): Boolean {
        val due = card.dueAt ?: card.state?.due
        return due == null || !due.isAfter(endOfDay)
    }

    fun priority(w: List<Double>, retr: Double, weakness: Double, importance: Double, proximity: Double): Double =
        w[0] * (1 - retr) + w[1] * weakness + w[2] * importance + w[3] * proximity

    private fun reason(retrAvg: Double, weakness: Double, importance: Double, topic: TopicFacts?, due: Int, reviewed: Int): String = when {
        topic == null -> REASON_BRIEFS
        reviewed > 0 && weakness >= 0.6 -> REASON_WEAK
        reviewed > 0 && retrAvg < 0.75 -> REASON_FADING
        importance >= 0.7 -> REASON_PAPERS
        due > 0 -> REASON_DUE
        else -> REASON_NEW
    }

    /** Full-week review: cards reviewed in the last 7 days that are not due yet but already fading (recall < 90 percent). */
    fun sundayExtraIds(engine: Fsrs, cards: List<QueueCard>, now: Instant, endOfDay: Instant): List<String> {
        val picked = ArrayList<Pair<Double, String>>()
        for (c in cards) {
            val st = c.state
            if (st == null || st.isNew || isDue(c, endOfDay)) continue
            val last = st.lastReview ?: continue
            if (Fsrs.wholeDays(last, now) > 7) continue
            val r = engine.retrievability(st, now)
            if (r < SUNDAY_RETRIEVABILITY) picked.add(r to c.id)
        }
        picked.sortWith(compareBy<Pair<Double, String>>({ it.first }, { it.second }))
        return picked.take(SUNDAY_EXTRA_CAP).map { it.second }
    }

    /** Smart-ordered groups for the day, before the rules. Inside a group: due first (most overdue first), Sunday extras, then new. */
    fun buildGroups(
        engine: Fsrs,
        cards: List<QueueCard>,
        topics: Map<String, TopicFacts>,
        now: Instant,
        endOfDay: Instant,
        cfg: RevisionConfig,
        isSunday: Boolean = false,
        snoozedTopics: Set<String> = emptySet(),
    ): List<QueueGroup> {
        val extraIds: Set<String> =
            if (isSunday && cfg.sundayReview) sundayExtraIds(engine, cards, now, endOfDay).toSet() else emptySet()
        val byGroup = LinkedHashMap<String, MutableList<QueueCard>>()
        for (c in cards) {
            if (c.topicId != null && c.topicId in snoozedTopics) continue
            if (isDue(c, endOfDay) || c.id in extraIds) byGroup.getOrPut(groupKey(c)) { ArrayList() }.add(c)
        }
        val out = ArrayList<QueueGroup>()
        for ((key, members) in byGroup) {
            val first = members[0]
            val topic: TopicFacts? = first.topicId?.let { topics[it] }
            val reviewed = members.filter { it.state != null && !it.state.isNew }
            val dueCards = members.filter { it.id !in extraIds && isDue(it, endOfDay) }
            val retrAvg = if (reviewed.isNotEmpty()) reviewed.sumOf { engine.retrievability(it.state, now) } / reviewed.size else 1.0
            var weakness = 0.3 // current affairs and other loose cards: a modest, steady pull
            var importance = 0.0
            var proximity = 0.0
            if (topic != null) {
                weakness = if (reviewed.isNotEmpty()) 1.0 - topic.strength.coerceIn(0.0, 1.0) else 0.5
                importance = topic.importance.coerceIn(0.0, 1.0)
                proximity = topic.examProximity.coerceIn(0.0, 1.0)
            }
            val allExtra = extraIds.isNotEmpty() && members.none { it.id !in extraIds } && reviewed.isNotEmpty()
            val why = if (allExtra) REASON_SUNDAY else reason(retrAvg, weakness, importance, topic, dueCards.size, reviewed.size)
            val ordered = members.sortedWith(
                compareBy<QueueCard>(
                    { it.state == null || it.state.isNew },
                    { it.id in extraIds },
                    { it.dueAt?.toEpochMilli() ?: 0L },
                    { it.id },
                ),
            )
            out.add(
                QueueGroup(
                    key = key,
                    topicId = first.topicId,
                    title = topic?.title ?: first.group,
                    subject = topic?.subject ?: first.group,
                    reason = why,
                    priority = priority(cfg.weights, retrAvg, weakness, importance, proximity),
                    cardIds = ordered.map { it.id },
                    due = dueCards.size - dueCards.count { it.state == null || it.state.isNew },
                    newCards = members.count { it.state == null || it.state.isNew },
                    extra = members.count { it.id in extraIds },
                ),
            )
        }
        out.sortWith(compareBy<QueueGroup>({ -it.priority }, { it.title.lowercase() }, { it.key }))
        return out
    }

    /**
     * Rules engine: new cards limited per day, pinned subject first, the daily group present (its first N cards always make
     * the cut), then the max cards per day cut from the bottom of the order.
     */
    fun applyRules(groups: List<QueueGroup>, cfg: RevisionConfig, allCards: Map<String, QueueCard>): List<QueueGroup> {
        var list: List<QueueGroup> = groups

        // 1. new cards per day: keep the first `newPerDay` new cards in the current order
        if (cfg.newPerDay >= 0) {
            var budget = cfg.newPerDay
            val next = ArrayList<QueueGroup>()
            for (g in list) {
                val keep = ArrayList<String>()
                var newLeft = g.newCards
                for (cid in g.cardIds) {
                    val card = allCards[cid]
                    val isNewCard = card != null && (card.state == null || card.state.isNew)
                    if (isNewCard) {
                        if (budget <= 0) {
                            newLeft -= 1
                            continue
                        }
                        budget -= 1
                    }
                    keep.add(cid)
                }
                if (keep.isNotEmpty()) next.add(g.copy(cardIds = keep, newCards = Math.max(newLeft, 0)))
            }
            list = next
        }

        // 2. pinned subject first
        var pinnedCount = 0
        val pinnedName = cfg.pinnedSubject?.trim()?.lowercase()
        if (!pinnedName.isNullOrEmpty()) {
            val pinned = list.filter { it.subject.trim().lowercase() == pinnedName || it.title.trim().lowercase() == pinnedName }
            val rest = list.filter { it !in pinned }
            val marked = pinned.map { g ->
                if (g.reason == REASON_WEAK || g.reason == REASON_FADING) g else g.copy(reason = REASON_PINNED)
            }
            list = marked + rest
            pinnedCount = marked.size
        }

        // 3. the daily group, moved to just after the pinned groups so the max-cards cut never removes it
        val dailyName = cfg.dailyGroup?.trim()?.lowercase()
        if (!dailyName.isNullOrEmpty()) {
            val daily = list.filter { it.key.lowercase() == "g:$dailyName" || it.title.trim().lowercase() == dailyName }
            if (daily.isNotEmpty()) {
                val limit = Math.max(cfg.dailyGroupCards, 0)
                val trimmed = daily.map { g ->
                    val ids = if (limit > 0) g.cardIds.take(limit) else g.cardIds
                    g.copy(cardIds = ids, due = Math.min(g.due, ids.size))
                }
                val others = list.filter { g -> daily.none { it.key == g.key } }
                val head = Math.min(pinnedCount, others.size)
                list = others.take(head) + trimmed + others.drop(head)
            }
        }
        return cutToMax(list, cfg.maxCards)
    }

    /** Keeps the first `maxCards` cards in queue order; groups that end up empty are dropped. */
    fun cutToMax(groups: List<QueueGroup>, maxCards: Int): List<QueueGroup> {
        var left = Math.max(maxCards, 0)
        val out = ArrayList<QueueGroup>()
        for (g in groups) {
            if (left <= 0) break
            var ids = g.cardIds
            var due = g.due
            var fresh = g.newCards
            var extra = g.extra
            if (ids.size > left) {
                ids = ids.take(left)
                due = Math.min(due, ids.size)
                fresh = Math.min(fresh, ids.size)
                extra = Math.min(extra, ids.size)
            }
            left -= ids.size
            if (ids.isNotEmpty()) out.add(g.copy(cardIds = ids, due = due, newCards = fresh, extra = extra))
        }
        return out
    }

    /** 'My order': groups named in `order` (by key) come first, in that order; the others follow in smart order. */
    fun applyManualOrder(groups: List<QueueGroup>, order: List<String>): List<QueueGroup> {
        val pos = HashMap<String, Int>()
        order.forEachIndexed { i, k -> pos[k] = i }
        val known = groups.filter { it.key in pos }.sortedBy { pos[it.key] ?: 0 }
        return known + groups.filter { it.key !in pos }
    }

    /** Day offsets (1 = tomorrow) for a snoozed topic's cards: spread over the next 2 to 3 days, never more than maxPerDay a day. */
    fun snoozeOffsets(count: Int, maxPerDay: Int = 80, spreadDays: Int = 3): List<Int> {
        if (count <= 0) return emptyList()
        var days = Math.max(1, Math.min(spreadDays, count))
        val needed = (count + Math.max(maxPerDay, 1) - 1) / Math.max(maxPerDay, 1)
        days = Math.max(days, needed)
        return List(count) { i -> 1 + (i * days) / count }
    }
}
