package com.naveen.civilscompanion

import com.naveen.civilscompanion.ui.voice.VoiceCommand
import com.naveen.civilscompanion.ui.voice.VoiceLogic
import com.naveen.civilscompanion.ui.voice.VoiceGrammar
import com.naveen.civilscompanion.ui.voice.doneText
import com.naveen.civilscompanion.data.model.DailyPlan
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceGrammarTest {
    @Test
    fun everyCommandOnTheChipsIsUnderstood() {
        assertEquals(VoiceCommand.ReadBrief, VoiceGrammar.parse("Read today's brief"))
        assertEquals(VoiceCommand.WhatsNext, VoiceGrammar.parse("What's next?"))
        assertEquals(VoiceCommand.StartRevision, VoiceGrammar.parse("Start revision"))
        assertEquals(VoiceCommand.ReadPage, VoiceGrammar.parse("Read this page"))
        assertEquals(VoiceCommand.MarkDone, VoiceGrammar.parse("Mark done"))
        assertEquals(VoiceCommand.Pause, VoiceGrammar.parse("Pause"))
        VoiceGrammar.EXAMPLES.forEach { assertNotNull(it, VoiceGrammar.parse(it)) }
    }

    @Test
    fun fillerWordsAndPunctuationAreIgnored() {
        assertEquals(VoiceCommand.WhatsNext, VoiceGrammar.parse("Hey, what's next?"))
        assertEquals(VoiceCommand.ReadBrief, VoiceGrammar.parse("Please read the morning brief"))
        assertEquals(VoiceCommand.ReadPage, VoiceGrammar.parse("read this page please"))
        assertEquals("whats next", VoiceGrammar.normalise("What’s next?"))
    }

    @Test
    fun playbackCommands() {
        assertEquals(VoiceCommand.Next, VoiceGrammar.parse("next"))
        assertEquals(VoiceCommand.Previous, VoiceGrammar.parse("go back"))
        assertEquals(VoiceCommand.Repeat, VoiceGrammar.parse("say that again"))
        assertEquals(VoiceCommand.Resume, VoiceGrammar.parse("continue"))
        assertEquals(VoiceCommand.Faster, VoiceGrammar.parse("speed up"))
        assertEquals(VoiceCommand.Slower, VoiceGrammar.parse("slow down"))
        assertEquals(VoiceCommand.SaveThis, VoiceGrammar.parse("save this"))
        assertEquals(VoiceCommand.MarkDone, VoiceGrammar.parse("mark it as done"))
    }

    @Test
    fun timerUnderstandsDigitsAndWords() {
        assertEquals(VoiceCommand.SetTimer(10), VoiceGrammar.parse("set timer 10 minutes"))
        assertEquals(VoiceCommand.SetTimer(25), VoiceGrammar.parse("set a timer for twenty five minutes"))
        assertEquals(VoiceCommand.SetTimer(15), VoiceGrammar.parse("set a 15 minute timer"))
        assertEquals(VoiceCommand.SetTimer(5), VoiceGrammar.parse("timer 5 mins"))
        assertNull(VoiceGrammar.parse("set a timer for banana minutes"))
        assertNull(VoiceGrammar.parse("set timer 0 minutes"))
    }

    @Test
    fun realQuestionsAreNotCommands() {
        assertNull(VoiceGrammar.parse("What is Article 21?"))
        assertNull(VoiceGrammar.parse("Next amendment procedure"))
        assertNull(VoiceGrammar.parse("Explain the basic structure doctrine"))
        assertNull(VoiceGrammar.parse("  "))
    }

    @Test
    fun revisionGrades() {
        assertEquals(1, VoiceGrammar.parseGrade("Again"))
        assertEquals(2, VoiceGrammar.parseGrade("hard."))
        assertEquals(3, VoiceGrammar.parseGrade("Good"))
        assertEquals(4, VoiceGrammar.parseGrade("easy"))
        assertNull(VoiceGrammar.parseGrade("banana"))
    }

    @Test
    fun numbersAndSpeeds() {
        assertEquals(45, VoiceGrammar.numberOf("forty five"))
        assertEquals(12, VoiceGrammar.numberOf("twelve"))
        assertNull(VoiceGrammar.numberOf("many"))
        assertEquals(1.1f, VoiceLogic.nextSpeed(1.0f, true), 0.001f)
        assertEquals(0.9f, VoiceLogic.nextSpeed(1.0f, false), 0.001f)
        assertEquals(2.0f, VoiceLogic.nextSpeed(2.0f, true), 0.001f)
        assertEquals(0.7f, VoiceLogic.nextSpeed(0.7f, false), 0.001f)
        assertEquals("Timer set for 10 minutes", VoiceCommand.SetTimer(10).doneText())
    }

    @Test
    fun nextPlanStepSkipsDoneAndSkippedBlocks() {
        fun block(id: String, start: String, title: String) = JsonObject(
            mapOf("id" to JsonPrimitive(id), "start" to JsonPrimitive(start), "title" to JsonPrimitive(title), "minutes" to JsonPrimitive(30)),
        )
        val plan = DailyPlan(
            id = "p1", date = "2026-09-20",
            blocks = listOf(block("b2", "10:00", "Polity"), block("b1", "07:00", "Brief"), block("b3", "18:00", "Revision")),
            completion = JsonObject(mapOf("b1" to JsonPrimitive("done"), "b2" to JsonPrimitive("skipped"))),
        )
        val step = VoiceLogic.nextStep(plan)
        assertEquals("b3", step?.id)
        assertEquals("Revision", step?.title)
        assertEquals(30, step?.minutes)
        val allDone = plan.copy(completion = JsonObject(mapOf("b1" to JsonPrimitive("done"), "b2" to JsonPrimitive("done"), "b3" to JsonPrimitive("done"))))
        assertNull(VoiceLogic.nextStep(allDone))
        assertEquals("1.25x", VoiceLogic.formatSpeed(1.25f))
    }
}
