package com.naveen.civilscompanion

import com.naveen.civilscompanion.ui.answers.AnswerLogic
import com.naveen.civilscompanion.ui.explain.ExplainLogic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainAnswerLogicTest {
    private val explainJson = Json.parseToJsonElement(
        """{"covered":["Article 21 is the right to life"],"missed":["Maneka Gandhi widened Article 21 in 1978","Procedure must be fair"],
           "needs_correcting":[{"said":"It is Article 20","correct":"It is Article 21"},{"said":"","correct":""}],
           "coverage":{"covered":1,"total":3},"model_explanation":"Article 21 protects life.","from_your_material":true}""",
    ).jsonObject

    @Test
    fun explainFeedbackIsReadFromServerJson() {
        val f = ExplainLogic.parseFeedback(explainJson)
        assertNotNull(f)
        assertEquals(1, f!!.covered.size)
        assertEquals(2, f.missed.size)
        assertEquals(1, f.fixes.size)
        assertEquals("It is Article 21", f.fixes[0].correct)
        assertEquals("Covered 1 of 3 key points", ExplainLogic.coverageText(f))
        assertTrue(f.fromMaterial)
        assertNull(ExplainLogic.parseFeedback(null))
    }

    @Test
    fun submitNeedsEnoughWords() {
        assertFalse(ExplainLogic.canSubmit("too short"))
        assertTrue(ExplainLogic.canSubmit("this explanation has exactly eight words in it"))
        assertEquals("3:05", ExplainLogic.durationLabel(185))
    }

    @Test
    fun missedPointBecomesAFillInTheBlankCard() {
        val (front, back) = ExplainLogic.cloze("Maneka Gandhi widened Article 21 in 1978", "Fundamental Rights")
        assertTrue(front.startsWith("Fundamental Rights: "))
        assertTrue(front.contains("_____"))
        assertFalse(front.contains("1978"))
        assertTrue(back.startsWith("1978"))
        assertTrue(back.contains("Maneka Gandhi widened Article 21 in 1978"))
    }

    @Test
    fun pointWithoutNumbersHidesTheLongestWord() {
        val (front, back) = ExplainLogic.cloze("Federalism divides powers between centre and states.", "Polity")
        assertTrue(front.contains("_____"))
        assertFalse(front.contains("Federalism"))
        assertTrue(back.startsWith("Federalism"))
    }

    @Test
    fun shortPointStillMakesACard() {
        val (front, back) = ExplainLogic.cloze("It is so", "Polity")
        assertTrue(front.startsWith("Polity: complete this point"))
        assertEquals("It is so", back)
    }

    private val answerJson = Json.parseToJsonElement(
        """{"structure":{"intro":"Good","body":"Fine","conclusion":"Missing"},"content_coverage":"Most","examples_data":"None",
           "word_limit":{"words":120,"limit":150,"comment":"Slightly short"},"presentation":"Neat","strengths":["Clear"],
           "improvements":["Add data","Add example"],"model_outline":["Intro","Body","Conclusion"],"transcript":"text","readable":true}""",
    ).jsonObject

    @Test
    fun answerFeedbackIsReadFromServerJson() {
        val f = AnswerLogic.parseFeedback(answerJson)
        assertNotNull(f)
        assertEquals("Missing", f!!.conclusion)
        assertEquals(120, f.words)
        assertEquals(150, f.limit)
        assertEquals(listOf("Intro", "Body", "Conclusion"), f.outline)
        assertTrue(f.readable)
        assertNull(AnswerLogic.parseFeedback(null))
    }

    @Test
    fun timerAndWordHelpers() {
        assertEquals(15, AnswerLogic.suggestedMinutes(250))
        assertEquals(9, AnswerLogic.suggestedMinutes(150))
        assertEquals(59, AnswerLogic.suggestedMinutes(1000))
        assertEquals("14:32", AnswerLogic.clock(872))
        assertEquals("+0:05", AnswerLogic.clock(-5))
        assertEquals("short", AnswerLogic.kindForLimit(150))
        assertEquals("mains", AnswerLogic.kindForLimit(250))
        assertEquals("essay", AnswerLogic.kindForLimit(1000))
        assertEquals(3, AnswerLogic.wordCount("  one two   three "))
        assertTrue(AnswerLogic.canSendTyped("one two three four five"))
        assertFalse(AnswerLogic.canSendTyped("one two"))
    }

    @Test
    fun scoresAndTrend() {
        assertEquals("7.5 / 10", AnswerLogic.scoreLabel(7.5))
        assertEquals("-", AnswerLogic.scoreLabel(null))
        assertEquals(1, AnswerLogic.scoreTone(8.0))
        assertEquals(2, AnswerLogic.scoreTone(5.0))
        assertEquals(3, AnswerLogic.scoreTone(2.0))
        assertEquals(1, AnswerLogic.lengthTone(240, 250))
        assertEquals(3, AnswerLogic.lengthTone(400, 250))
        assertEquals(2, AnswerLogic.lengthTone(100, 250))
        assertEquals(listOf(0.5f, 1.0f), AnswerLogic.trend(listOf(5.0, 12.0)))
    }
}
