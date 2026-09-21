package com.naveen.civilscompanion

import com.naveen.civilscompanion.data.model.Mcq
import com.naveen.civilscompanion.data.model.Mistake
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.ui.tests.Answered
import com.naveen.civilscompanion.ui.tests.MistakeRules
import com.naveen.civilscompanion.ui.tests.TestLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestLogicTest {
    private val polity = Topic(id = "s1", title = "Polity", level = 1)
    private val topic = Topic(id = "t1", title = "Preamble", level = 2, parentId = "s1")
    private val topics = mapOf("s1" to polity, "t1" to topic, "p0" to Topic(id = "p0", title = "GS 2", level = 0))

    private fun mcq(id: String, answer: Int = 1, topicId: String? = "t1") =
        Mcq(id = id, question = "Question $id", options = listOf("a", "b", "c", "d"), answerIndex = answer, topicId = topicId)

    @Test
    fun marksWithAndWithoutNegative() {
        assertEquals(10.0, TestLogic.marks(10, 3, false), 0.0001)
        assertEquals(9.0, TestLogic.marks(10, 3, true), 0.0001)
        assertEquals(0.33, TestLogic.marks(1, 2, true), 0.0001)
        assertEquals("9", TestLogic.scoreText(9.0))
        assertEquals("0.33", TestLogic.scoreText(0.33))
        assertEquals("0.5", TestLogic.scoreText(0.5))
    }

    @Test
    fun defaultMistakeTypeFromConfidence() {
        assertEquals("silly", TestLogic.defaultMistakeType("sure"))
        assertEquals("confused", TestLogic.defaultMistakeType("unsure"))
        assertEquals("didnt_know", TestLogic.defaultMistakeType("guess"))
        assertEquals("didnt_know", TestLogic.defaultMistakeType(""))
        assertEquals("didnt_know", TestLogic.defaultMistakeType(null))
    }

    @Test
    fun subjectIsTheLevelOneAncestor() {
        assertEquals("Polity", TestLogic.subjectOf("t1", topics))
        assertEquals("Polity", TestLogic.subjectOf("s1", topics))
        assertEquals("Other", TestLogic.subjectOf(null, topics))
        assertEquals("Other", TestLogic.subjectOf("missing", topics))
        assertEquals("GS 2", TestLogic.subjectOf("p0", topics))
    }

    @Test
    fun analysisCountsEverything() {
        val items = listOf(
            Answered(mcq("1"), 1, "sure", ""),
            Answered(mcq("2"), 0, "guess", "didnt_know"),
            Answered(mcq("3"), 2, "sure", "silly"),
            Answered(mcq("4"), -1, "", ""),
        )
        val a = TestLogic.analyse(items, topics, negative = true)
        assertEquals(4, a.total)
        assertEquals(1, a.correct)
        assertEquals(2, a.wrong)
        assertEquals(1, a.skipped)
        assertEquals(0.33, a.score, 0.0001)
        assertEquals(1, a.mistakes["didnt_know"])
        assertEquals(1, a.mistakes["silly"])
        assertEquals(0, a.mistakes["confused"])
        assertEquals("Polity", a.subjects.first().subject)
        assertEquals(4, a.subjects.first().total)
        assertEquals(listOf("Preamble" to 1), a.studyTopics)
        assertEquals(1, a.confidence.getValue("guess").answered)
        assertTrue(a.guessMessage.startsWith("You guessed 1 question and got 0 right"))
        assertEquals(2, a.tips.size)
    }

    @Test
    fun unmarkedWrongAnswerCountsAsDidntKnow() {
        val a = TestLogic.analyse(listOf(Answered(mcq("1"), 0, "", "")), topics, negative = false)
        assertEquals(1, a.mistakes["didnt_know"])
        assertEquals("You did not mark any answer as a guess.", a.guessMessage)
    }

    @Test
    fun clockAndRemaining() {
        assertEquals("30:00", TestLogic.clock(1800))
        assertEquals("00:05", TestLogic.clock(5))
        assertEquals("00:00", TestLogic.clock(-4))
        assertEquals(1790L, TestLogic.remainingSeconds(1_000_000L, 30, 1_010_000L))
        assertEquals(33, TestLogic.percent(1, 3))
        assertEquals(0, TestLogic.percent(0, 0))
    }

    @Test
    fun wrongAnswerAddsToTheBookAndResetsTheStreak() {
        val now = 1_700_000_000_000L
        val m = MistakeRules.onWrong(null, "id1", "q1", 2, "confused", now)
        assertEquals("q1", m.mcqId)
        assertEquals(0, m.streak)
        assertFalse(m.resolved)
        assertEquals(now + MistakeRules.DAY_MS, TimeUtil.parse(m.nextDueAt))
        val again = MistakeRules.onWrong(m.copy(streak = 1), "other", "q1", 0, "silly", now + 5)
        assertEquals("id1", again.id)
        assertEquals(0, again.streak)
        assertEquals("silly", again.mistakeType)
    }

    @Test
    fun twoCorrectAnswersSpacedApartClearAMistake() {
        val start = 1_700_000_000_000L
        val m = MistakeRules.onWrong(null, "id1", "q1", 2, "didnt_know", start)
        // too soon: nothing changes
        assertEquals(m, MistakeRules.onCorrect(m, start + 1000))
        val day2 = start + 2 * MistakeRules.DAY_MS
        val first = MistakeRules.onCorrect(m, day2)
        assertEquals(1, first.streak)
        assertFalse(first.resolved)
        assertEquals(day2 + 3 * MistakeRules.DAY_MS, TimeUtil.parse(first.nextDueAt))
        // answered again the same day: still not counted
        assertEquals(first, MistakeRules.onCorrect(first, day2 + 5000))
        val done = MistakeRules.onCorrect(first, day2 + 4 * MistakeRules.DAY_MS)
        assertTrue(done.resolved)
        assertEquals(2, done.streak)
        assertNull(done.nextDueAt)
        assertEquals(done, MistakeRules.onCorrect(done, day2 + 10 * MistakeRules.DAY_MS))
    }

    @Test
    fun retestOrdersDueFirstAndSkipsCleared() {
        val now = 1_700_000_000_000L
        fun row(id: String, mcq: String, dueOffsetDays: Long?, resolved: Boolean = false) = Mistake(
            id = id, mcqId = mcq, resolved = resolved,
            nextDueAt = dueOffsetDays?.let { TimeUtil.toIso(now + it * MistakeRules.DAY_MS) },
        )
        val all = listOf(
            row("a", "q1", 3), row("b", "q2", -2), row("c", "q3", null), row("d", "q4", -5, resolved = true),
            row("e", "q2", -1), row("f", "q5", -1),
        )
        // q3 has no due date so it counts as due and comes first
        assertEquals(listOf("q3", "q2", "q5", "q1"), MistakeRules.retestIds(all.filter { it.id != "e" }, now))
        assertEquals(listOf("q3", "q2", "q5"), MistakeRules.retestIds(all, now, dueOnly = true))
        assertEquals(2, MistakeRules.retestIds(all, now, limit = 2).size)
        assertTrue(MistakeRules.isDue(all[2], now))
        assertFalse(MistakeRules.isDue(all[0], now))
        assertFalse(MistakeRules.isDue(all[3], now))
    }
}
