package com.naveen.civilscompanion

import com.naveen.civilscompanion.ui.ask.AskLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskLogicTest {
    private val note = """
        # Fundamental Rights

        Part III of the Constitution guarantees the fundamental rights to every citizen.

        Article 21 protects the right to life and personal liberty. The Supreme Court widened it in Maneka Gandhi.

        Short line.
    """.trimIndent()

    @Test
    fun keywordsDropCommonWords() {
        val kw = AskLogic.keywords("What does Article 21 protect?")
        assertTrue("article" in kw)
        assertTrue("21" in kw)
        assertFalse("what" in kw)
        assertFalse("does" in kw)
    }

    @Test
    fun bestParagraphIsTheOneWithMostSharedWords() {
        val kw = AskLogic.keywords("Which article protects the right to life?")
        val best = AskLogic.bestParagraph(note, kw)
        assertNotNull(best)
        assertTrue(best!!.paragraph.startsWith("Article 21"))
        assertTrue(best.shared >= 3)
    }

    @Test
    fun pluralsMatch() {
        val kw = AskLogic.keywords("fundamental right")
        val best = AskLogic.bestParagraph(note, kw)
        assertNotNull(best)
        assertTrue(best!!.shared >= 2)
    }

    @Test
    fun nothingSharedMeansNoAnswer() {
        assertNull(AskLogic.bestParagraph(note, AskLogic.keywords("monsoon rainfall pattern")))
        assertNull(AskLogic.bestParagraph(note, emptyList()))
    }

    @Test
    fun goodEnoughRules() {
        assertFalse(AskLogic.goodEnough(0, 0))
        assertTrue(AskLogic.goodEnough(1, 1))
        assertFalse(AskLogic.goodEnough(2, 1))
        assertTrue(AskLogic.goodEnough(2, 2))
        assertFalse(AskLogic.goodEnough(6, 2))
        assertTrue(AskLogic.goodEnough(4, 2))
    }

    @Test
    fun speechTextHasNoMarkdownOrCitations() {
        val spoken = AskLogic.plainForSpeech("## Answer\n\n**Article 21** protects life [1].\n\n- point one [2]\n- see [the site](http://x.y)")
        assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("**"))
        assertFalse(spoken.contains("[1]"))
        assertFalse(spoken.contains("http"))
        assertTrue(spoken.contains("Article 21 protects life"))
        assertTrue(spoken.contains("the site"))
    }

    @Test
    fun displayTextHidesCitationNumbers() {
        assertEquals("It is so. Really.", AskLogic.forDisplay("It is so [1]. Really[2]."))
    }

    @Test
    fun sentencesAreSplit() {
        assertEquals(listOf("One.", "Two!", "Three?"), AskLogic.splitSentences("One. Two! Three?"))
        assertEquals(listOf("Line one", "Line two."), AskLogic.splitSentences("Line one\nLine two."))
    }

    @Test
    fun teluguIsRecognised() {
        assertTrue(AskLogic.isTelugu("భారత రాజ్యాంగం అంటే ఏమిటి?"))
        assertFalse(AskLogic.isTelugu("What is the Constitution?"))
        assertFalse(AskLogic.isTelugu("123"))
    }

    @Test
    fun timeLabelsAreInIndiaTime() {
        assertEquals("5:30 pm", AskLogic.timeLabel("2026-09-20T12:00:00Z"))
        assertEquals("", AskLogic.timeLabel("not a time"))
        assertEquals("20 Sep, 5:30 pm", AskLogic.dayTimeLabel("2026-09-20T12:00:00Z"))
    }

    @Test
    fun chatTitleIsShort() {
        assertEquals("What is FRBM?", AskLogic.chatTitle("  What is   FRBM? "))
        assertTrue(AskLogic.chatTitle("x".repeat(100)).length <= 60)
    }
}
