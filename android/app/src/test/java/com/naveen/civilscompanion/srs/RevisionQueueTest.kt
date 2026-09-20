package com.naveen.civilscompanion.srs

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionQueueTest {
    private val engine = Fsrs()
    private val now = Instant.parse("2026-09-21T06:00:00Z") // Monday, 11:30 in India
    private val endOfDay = Instant.parse("2026-09-21T18:29:59.999Z") // 23:59:59.999 in India
    private val cfg = RevisionConfig()

    private fun state(daysAgo: Long, stability: Double, at: Instant = now): CardMemory {
        val last = at.minus(Duration.ofDays(daysAgo))
        return CardMemory(stability, 5.0, last.plus(Duration.ofDays(stability.toLong())), last, 2, 0)
    }

    private fun card(id: String, topic: String?, group: String, st: CardMemory?, due: Instant? = null) =
        QueueCard(id, topic, group, st, due ?: st?.due ?: now.minus(Duration.ofDays(1)))

    private fun groups(cards: List<QueueCard>, topics: Map<String, TopicFacts>, c: RevisionConfig = cfg) =
        RevisionQueue.applyRules(RevisionQueue.buildGroups(engine, cards, topics, now, endOfDay, c), c, cards.associateBy { it.id })

    @Test
    fun weakTopicsComeFirstWithAReason() {
        val topics = mapOf(
            "weak" to TopicFacts("weak", "Weak topic", "Polity", strength = 0.1, importance = 0.5),
            "fine" to TopicFacts("fine", "Fine topic", "Polity", strength = 0.9, importance = 0.5),
        )
        val cards = (0..2).map { card("w$it", "weak", "Polity", state(10, 4.0)) } +
            (0..2).map { card("f$it", "fine", "Polity", state(8, 5.0)) } +
            card("n1", null, "Current affairs", null)
        val out = groups(cards, topics)
        assertEquals("Weak topic", out[0].title)
        assertEquals(RevisionQueue.REASON_WEAK, out[0].reason)
        val news = out.first { it.title == "Current affairs" }
        assertEquals(RevisionQueue.REASON_BRIEFS, news.reason)
        assertEquals(1, news.newCards)
        assertEquals(7, out.sumOf { it.count })
    }

    @Test
    fun onlyDueCardsAndNewCardLimit() {
        val topics = mapOf("t" to TopicFacts("t", "T", "S"))
        val cards = listOf(card("notdue", "t", "S", state(1, 30.0)), card("due", "t", "S", state(9, 3.0))) +
            (0..4).map { card("new$it", "t", "S", null) }
        val out = groups(cards, topics, cfg.copy(newPerDay = 2))
        assertEquals(3, out[0].count)
        assertEquals(2, out[0].newCards)
        assertEquals(1, out[0].due)
    }

    @Test
    fun maxCardsPinnedSubjectAndDailyGroup() {
        val topics = mapOf(
            "tp" to TopicFacts("tp", "Polity topic", "Polity", strength = 0.9),
            "tg" to TopicFacts("tg", "Geo topic", "Geography", strength = 0.0, importance = 0.9),
        )
        val cards = (0..5).map { card("p$it", "tp", "Polity", state(8, 5.0)) } +
            (0..5).map { card("g$it", "tg", "Geography", state(10, 3.0)) } +
            (0..5).map { card("c$it", null, "Current affairs", null) }
        val out = groups(cards, topics, cfg.copy(maxCards = 10, pinnedSubject = "Polity", dailyGroup = "Current affairs", dailyGroupCards = 3))
        assertEquals(10, out.sumOf { it.count })
        assertEquals("Polity topic", out[0].title)
        assertEquals(RevisionQueue.REASON_PINNED, out[0].reason)
        assertEquals(listOf("Polity topic", "Current affairs"), out.map { it.title }.take(2))
        assertEquals(3, out.first { it.title == "Current affairs" }.count)
    }

    @Test
    fun manualOrderPutsNamedGroupsFirst() {
        fun g(k: String) = QueueGroup(k, k, k, "s", "", 0.0, listOf("x"))
        assertEquals(listOf("c", "a", "b"), RevisionQueue.applyManualOrder(listOf(g("a"), g("b"), g("c")), listOf("c", "a")).map { it.key })
    }

    @Test
    fun snoozeSpreadsCardsOverThreeDays() {
        assertEquals(emptyList<Int>(), RevisionQueue.snoozeOffsets(0))
        assertEquals(listOf(1), RevisionQueue.snoozeOffsets(1))
        assertEquals(listOf(1, 1, 2, 2, 3, 3), RevisionQueue.snoozeOffsets(6))
        val small = RevisionQueue.snoozeOffsets(10, maxPerDay = 2)
        assertEquals(5, small.max())
        assertTrue(small.toSet().all { d -> small.count { it == d } <= 2 })
    }

    @Test
    fun snoozedCardsLeaveTodaysQueue() {
        val topics = mapOf("t" to TopicFacts("t", "T", "S"))
        val later = now.plus(Duration.ofDays(2))
        val cards = listOf(card("a", "t", "S", state(10, 3.0), due = later), card("b", "t", "S", null, due = later))
        assertTrue(groups(cards, topics).isEmpty())
    }

    @Test
    fun sundayReviewAddsFadingCardsOfTheWeek() {
        val sunday = Instant.parse("2026-09-20T06:00:00Z")
        val sundayEnd = Instant.parse("2026-09-20T18:29:59.999Z")
        val fading = QueueCard("fading", "t", "S", state(5, 3.0, sunday), sunday.plus(Duration.ofDays(2)))
        val strong = QueueCard("strong", "t", "S", state(1, 40.0, sunday), sunday.plus(Duration.ofDays(39)))
        val old = QueueCard("old", "t", "S", state(30, 100.0, sunday), sunday.plus(Duration.ofDays(70)))
        val ids = RevisionQueue.sundayExtraIds(engine, listOf(fading, strong, old), sunday, sundayEnd)
        assertEquals(listOf("fading"), ids)
        val topics = mapOf("t" to TopicFacts("t", "T", "S"))
        val out = RevisionQueue.buildGroups(engine, listOf(fading, strong, old), topics, sunday, sundayEnd, cfg, isSunday = true)
        assertEquals(RevisionQueue.REASON_SUNDAY, out[0].reason)
        assertEquals(listOf("fading"), out[0].cardIds)
        val off = RevisionQueue.buildGroups(engine, listOf(fading, strong, old), topics, sunday, sundayEnd, cfg.copy(sundayReview = false), isSunday = true)
        assertTrue(off.isEmpty())
    }

    @Test
    fun minutesRoundLikeTheServer() {
        val g = QueueGroup("k", null, "t", "s", "", 0.0, List(7) { "c$it" })
        assertEquals(5, g.minutes(0.7))
        assertEquals(0, QueueGroup("k", null, "t", "s", "", 0.0, emptyList()).minutes(0.7))
    }
}
