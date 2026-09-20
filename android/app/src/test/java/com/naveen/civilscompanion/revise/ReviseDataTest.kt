package com.naveen.civilscompanion.revise

import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.data.model.RevisionOrder
import com.naveen.civilscompanion.data.model.RevisionRule
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.srs.CardMemory
import com.naveen.civilscompanion.srs.FsrsTime
import com.naveen.civilscompanion.ui.revise.HandsFree
import com.naveen.civilscompanion.ui.revise.ReviseData
import com.naveen.civilscompanion.ui.revise.RevisionSettings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviseDataTest {
    private val now = Instant.parse("2026-09-21T06:00:00Z") // Monday 11:30 India

    private fun reviewed(daysAgo: Long, stability: Double): JsonObject {
        val last = now.minus(Duration.ofDays(daysAgo))
        return CardMemory(stability, 5.0, last.plus(Duration.ofDays(stability.toLong())), last, 2, 0).toJson()
    }

    private fun card(id: String, topic: String?, state: JsonObject?, group: String = "Polity"): Card {
        val due = state?.let { CardMemory.fromJson(it).due } ?: now.minus(Duration.ofDays(1))
        return Card(id = id, front = "Q $id", back = "A $id", topicId = topic, group = group, fsrsState = state, dueAt = FsrsTime.format(due))
    }

    private val polity = Topic(id = "p", title = "Polity", level = 1, approved = true)
    private val weak = Topic(id = "w", title = "Fundamental Rights", parentId = "p", level = 2, strength = 0.1, importance = 8.0, examTags = listOf("UPSC"))
    private val fine = Topic(id = "f", title = "Parliament", parentId = "p", level = 2, strength = 0.9, importance = 3.0)

    private fun queue(cards: List<Card>, rules: List<RevisionRule> = emptyList(), orders: List<RevisionOrder> = emptyList(), s: RevisionSettings = RevisionSettings()) =
        ReviseData.buildQueue(cards, listOf(polity, weak, fine), emptyList(), rules, orders, s, now)

    @Test
    fun queueRanksWeakTopicFirstAndCountsMinutes() {
        val cards = (0..2).map { card("w$it", "w", reviewed(10, 4.0)) } +
            (0..2).map { card("f$it", "f", reviewed(8, 5.0)) } +
            card("n1", null, null, "Current affairs")
        val q = queue(cards)
        assertEquals("Fundamental Rights", q.groups[0].title)
        assertEquals("Weak", q.groups[0].reason)
        assertEquals("Polity", q.groups[0].subject)
        assertEquals(7, q.totalCards)
        assertEquals(5, q.minutes)
        assertEquals("2026-09-21", q.date)
        assertEquals("smart", q.mode)
        assertTrue(q.loaded)
    }

    @Test
    fun rulesAndSettingsChangeTheQueue() {
        val cards = (0..5).map { card("w$it", "w", reviewed(10, 4.0)) } + (0..5).map { card("c$it", null, null, "Current affairs") }
        val rules = listOf(
            RevisionRule("r1", "daily_group", JsonObject(mapOf("group" to JsonPrimitive("Current affairs"), "cards" to JsonPrimitive(2))), true),
            RevisionRule("r2", "pinned_subject", JsonObject(mapOf("subject" to JsonPrimitive("Polity"))), false),
        )
        val q = queue(cards, rules, s = RevisionSettings(maxCards = 7))
        assertEquals(7, q.totalCards)
        assertEquals(2, q.groups.first { it.title == "Current affairs" }.count)
        assertEquals("Current affairs", q.config.dailyGroup)
        assertNull(q.config.pinnedSubject) // that rule is switched off
        assertEquals(7, q.config.maxCards)
    }

    @Test
    fun myOrderIsUsedAndTheNewestRowWins() {
        val cards = listOf(card("w1", "w", reviewed(10, 4.0)), card("f1", "f", reviewed(8, 5.0)))
        assertEquals("w", queue(cards).groups[0].key)
        val orders = listOf(
            RevisionOrder("o1", "2026-09-21", listOf("w", "f"), updatedAt = "2026-09-21T01:00:00Z"),
            RevisionOrder("o2", "2026-09-21", listOf("f", "w"), updatedAt = "2026-09-21T02:00:00Z"),
            RevisionOrder("o3", "2026-09-20", listOf("w", "f"), updatedAt = "2026-09-21T03:00:00Z"),
        )
        val q = queue(cards, orders = orders)
        assertEquals("my", q.mode)
        assertEquals(listOf("f", "w"), q.groups.map { it.key })
    }

    @Test
    fun sundayIsDetected() {
        val sunday = Instant.parse("2026-09-20T06:00:00Z")
        val q = ReviseData.buildQueue(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), RevisionSettings(), sunday)
        assertTrue(q.isSunday)
        val off = ReviseData.buildQueue(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), RevisionSettings(sundayReview = false), sunday)
        assertFalse(off.isSunday)
    }

    @Test
    fun examProximityFollowsTheNearestMatchingExam() {
        val today = LocalDate.of(2026, 9, 20)
        val exams = listOf(
            Exam(id = "e1", name = "UPSC CSE", date = "2026-12-19T00:00:00Z"), // 90 days
            Exam(id = "e2", name = "APPSC Group-I", date = null),
            Exam(id = "e3", name = "UPSC CSE", date = "2026-01-01T00:00:00Z"), // past
        )
        assertEquals(0.5, ReviseData.examProximity(listOf("UPSC"), exams, today), 1e-9)
        assertEquals(0.0, ReviseData.examProximity(listOf("APPSC"), exams, today), 0.0)
        assertEquals(0.5, ReviseData.examProximity(emptyList(), exams, today), 1e-9)
        assertEquals(0.0, ReviseData.examProximity(listOf("UPSC"), emptyList(), today), 0.0)
    }

    @Test
    fun movingGroupsAndSnoozeTimes() {
        assertEquals(listOf("b", "a", "c"), ReviseData.move(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "c", "b"), ReviseData.move(listOf("a", "b", "c"), "b", 1))
        assertEquals(listOf("a", "b"), ReviseData.move(listOf("a", "b"), "a", -1))
        assertEquals(listOf("a", "b"), ReviseData.move(listOf("a", "b"), "zzz", 1))
        // 6 in the morning in India = 00:30 UTC
        assertEquals("2026-09-22T00:30:00.000Z", ReviseData.snoozeDue(LocalDate.of(2026, 9, 21), 1))
    }

    @Test
    fun labelsSpeechAndTimes() {
        assertEquals("1 d", ReviseData.intervalLabel(1))
        assertEquals("29 d", ReviseData.intervalLabel(29))
        assertEquals("2 mo", ReviseData.intervalLabel(60))
        assertEquals("1.5 y", ReviseData.intervalLabel(548))
        assertEquals("Article 21 protects , blank ,", ReviseData.speechText("**Article 21** protects ..."))
        assertTrue(ReviseData.isValidTime("18:00"))
        assertTrue(ReviseData.isValidTime("7:30"))
        assertFalse(ReviseData.isValidTime("25:00"))
        assertFalse(ReviseData.isValidTime("18:5"))
        assertFalse(ReviseData.isValidTime("1800"))
    }

    @Test
    fun handsFreeWords() {
        assertEquals(1, HandsFree.parseGrade("again please"))
        assertEquals(2, HandsFree.parseGrade("that was hard"))
        assertEquals(3, HandsFree.parseGrade("Good"))
        assertEquals(4, HandsFree.parseGrade("easy one"))
        assertNull(HandsFree.parseGrade("hmm the constitution"))
        assertTrue(HandsFree.wantsReveal("show the answer"))
        assertFalse(HandsFree.wantsReveal("banana"))
        assertTrue(HandsFree.wantsStop("stop now"))
    }
}
