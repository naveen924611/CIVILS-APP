package com.naveen.civilscompanion.ui.revise

import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.data.model.RevisionOrder
import com.naveen.civilscompanion.data.model.RevisionRule
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.srs.CardMemory
import com.naveen.civilscompanion.srs.Fsrs
import com.naveen.civilscompanion.srs.FsrsTime
import com.naveen.civilscompanion.srs.QueueCard
import com.naveen.civilscompanion.srs.QueueGroup
import com.naveen.civilscompanion.srs.RevisionConfig
import com.naveen.civilscompanion.srs.RevisionQueue
import com.naveen.civilscompanion.srs.TopicFacts
import com.naveen.civilscompanion.ui.today.ExamDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Owner settings for revision (KV keys revision.*, see docs/build-guide.md section 9.1). */
data class RevisionSettings(
    val maxCards: Int = 80,
    val newPerDay: Int = 20,
    val sundayReview: Boolean = true,
    val slotTime: String = "18:00",
    val retention: Double = Fsrs.DEFAULT_RETENTION,
)

/** The day's queue, ready to show. */
data class QueueState(
    val date: String = "",
    val mode: String = "smart", // smart | my
    val groups: List<QueueGroup> = emptyList(),
    val totalCards: Int = 0,
    val minutes: Int = 0,
    val isSunday: Boolean = false,
    val cards: Map<String, Card> = emptyMap(),
    val topics: Map<String, TopicFacts> = emptyMap(),
    val config: RevisionConfig = RevisionConfig(),
    val loaded: Boolean = false,
)

object ReviseData {
    val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

    fun endOfDay(day: LocalDate): Instant = day.plusDays(1).atStartOfDay(INDIA).toInstant().minusMillis(1)

    fun startOfDay(day: LocalDate): Instant = day.atStartOfDay(INDIA).toInstant()

    /** Subject name = the level 1 ancestor of the topic; falls back to the topic's own title. */
    fun subjectOf(topic: Topic, byId: Map<String, Topic>): String {
        var node: Topic? = topic
        val seen = HashSet<String>()
        while (node != null && seen.add(node.id)) {
            if (node.level == 1) return node.title
            node = node.parentId?.let { byId[it] }
        }
        return topic.title
    }

    /** 1.0 when an exam this topic belongs to is today, falling to 0 at 180 days or more; 0 when no date is known. */
    fun examProximity(tags: List<String>, exams: List<Exam>, today: LocalDate): Double {
        var best = 0.0
        val lower = tags.map { it.lowercase() }
        for (e in exams) {
            val left = ExamDates.daysLeft(e.date, today) ?: continue
            if (left < 0) continue
            // Whole-word match, so the tag "SI" cannot match inside another word ("Civil services").
            val words = e.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (lower.isNotEmpty() && lower.none { it in words }) continue
            best = Math.max(best, 1.0 - Math.min(left, 180) / 180.0)
        }
        return best
    }

    fun facts(topics: List<Topic>, exams: List<Exam>, today: LocalDate): Map<String, TopicFacts> {
        val byId = topics.associateBy { it.id }
        return topics.associate { t ->
            t.id to TopicFacts(
                id = t.id,
                title = t.title,
                subject = subjectOf(t, byId),
                strength = t.strength.coerceIn(0.0, 1.0),
                importance = (t.importance / 10.0).coerceIn(0.0, 1.0),
                examProximity = examProximity(t.examTags, exams, today),
            )
        }
    }

    fun queueCard(c: Card): QueueCard = QueueCard(
        id = c.id,
        topicId = c.topicId,
        group = c.group.ifEmpty { "Cards" },
        state = c.fsrsState?.let { CardMemory.fromJson(it) },
        dueAt = FsrsTime.parse(c.dueAt),
    )

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** Settings first, then enabled rule rows: pinned_subject {subject}, daily_group {group, cards}, max_cards {n}, sunday_review {enabled}. */
    fun config(s: RevisionSettings, rules: List<RevisionRule>): RevisionConfig {
        var cfg = RevisionConfig(maxCards = Math.max(s.maxCards, 5), newPerDay = Math.max(s.newPerDay, 0), sundayReview = s.sundayReview)
        for (r in rules) {
            if (!r.enabled) continue
            val p = r.params
            when (r.type) {
                "pinned_subject" -> p.str("subject")?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(pinnedSubject = it) }
                "daily_group" -> p.str("group")?.takeIf { it.isNotBlank() }?.let {
                    cfg = cfg.copy(dailyGroup = it, dailyGroupCards = Math.max(p.int("cards") ?: 10, 1))
                }
                "max_cards" -> p.int("n")?.let { cfg = cfg.copy(maxCards = Math.max(it, 5)) }
                "sunday_review" -> cfg = cfg.copy(sundayReview = (p["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
            }
        }
        return cfg
    }

    /** Builds the day's queue exactly like the server (backend/app/features/revision/service.py build_queue). */
    fun buildQueue(
        cards: List<Card>,
        topics: List<Topic>,
        exams: List<Exam>,
        rules: List<RevisionRule>,
        orders: List<RevisionOrder>,
        settings: RevisionSettings,
        now: Instant,
        smartOnly: Boolean = false,
    ): QueueState {
        val day = now.atZone(INDIA).toLocalDate()
        val cfg = config(settings, rules)
        val facts = facts(topics, exams, day)
        val qcards = cards.map { queueCard(it) }
        val byId = qcards.associateBy { it.id }
        val engine = Fsrs.forRetention(settings.retention)
        val isSunday = day.dayOfWeek.value == 7
        var groups = RevisionQueue.buildGroups(engine, qcards, facts, now, endOfDay(day), cfg, isSunday = isSunday)
        groups = RevisionQueue.applyRules(groups, cfg, byId)
        val order = if (smartOnly) null else orders.filter { it.date == day.toString() && !it.deleted }.maxByOrNull { it.updatedAt }?.groupOrder
        var mode = "smart"
        if (!order.isNullOrEmpty()) {
            groups = RevisionQueue.cutToMax(RevisionQueue.applyManualOrder(groups, order), cfg.maxCards)
            mode = "my"
        }
        val total = groups.sumOf { it.count }
        return QueueState(
            date = day.toString(),
            mode = mode,
            groups = groups,
            totalCards = total,
            minutes = Math.rint(total * cfg.minutesPerCard).toInt(),
            isSunday = isSunday && cfg.sundayReview,
            cards = cards.associateBy { it.id },
            topics = facts,
            config = cfg,
            loaded = true,
        )
    }

    /** Moves the group with `key` one place up (-1) or down (+1) in the shown order; returns the new list of keys. */
    fun move(keys: List<String>, key: String, delta: Int): List<String> {
        val i = keys.indexOf(key)
        val j = i + delta
        if (i < 0 || j < 0 || j >= keys.size) return keys
        val out = keys.toMutableList()
        out[i] = keys[j]
        out[j] = keys[i]
        return out
    }

    /** ISO time for a snoozed card: 6 in the morning (India) of today + offset days. */
    fun snoozeDue(today: LocalDate, offsetDays: Int): String {
        val at = today.plusDays(offsetDays.toLong()).atStartOfDay(INDIA).toInstant().plus(6, ChronoUnit.HOURS)
        return FsrsTime.format(at)
    }

    /** "1 d", "3 d", "2 mo", "1.5 y" for the grade buttons. */
    fun intervalLabel(days: Int): String = when {
        days < 30 -> "$days d"
        days < 365 -> "${Math.max(1, Math.rint(days / 30.4).toInt())} mo"
        else -> "%.1f y".format(java.util.Locale.ROOT, days / 365.0)
    }

    /** Text for the tablet's voice: no markdown marks, and "..." (a blank to fill) is read as "blank". */
    fun speechText(text: String): String =
        text.replace("...", ", blank, ").replace(Regex("[*_#`>]+"), "").replace(Regex("\\s+"), " ").trim()

    /** "18:00" or "7:30": a 24-hour time. */
    fun isValidTime(text: String): Boolean {
        val parts = text.split(":")
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        return parts[0].length in 1..2 && parts[1].length == 2 && h in 0..23 && m in 0..59
    }
}
