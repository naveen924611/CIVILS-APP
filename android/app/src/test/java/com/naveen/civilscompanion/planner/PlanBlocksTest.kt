package com.naveen.civilscompanion.planner

import com.naveen.civilscompanion.ui.today.ExamDates
import com.naveen.civilscompanion.ui.today.PlanBlocks
import com.naveen.civilscompanion.ui.today.StudyPrefs
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanBlocksTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun parsesAServerBlockAndSkipsBrokenOnes() {
        val b = PlanBlocks.parse(
            obj("""{"id":"2026-09-21-revision","kind":"revision","start":"18:00","minutes":40,"title":"Revision","detail":"40 cards due","topic_id":null,"ref":"revise/session"}"""),
        )!!
        assertEquals("revision", b.kind)
        assertEquals(40, b.minutes)
        assertNull(b.topicId)
        assertEquals("revise/session", b.ref)
        assertNull(PlanBlocks.parse(obj("""{"kind":"study"}""")))
        val all = PlanBlocks.parseAll(listOf(obj("""{"id":"b","start":"10:00"}"""), obj("""{"id":"a","start":"07:00","minutes":30.0}"""), obj("{}")))
        assertEquals(listOf("a", "b"), all.map { it.id })
        assertEquals(30, all[0].minutes)
    }

    @Test
    fun fallbackPlanAddsUpToTheHoursAndKeepsTheRevisionSlot() {
        val blocks = PlanBlocks.fallback("2026-09-21", 4.0, "07:00", "19:00", "18:00", dueCards = 60, teluguMinutes = 15)
        assertEquals(240, PlanBlocks.totalMinutes(blocks))
        assertEquals(blocks.size, blocks.map { it.id }.toSet().size)
        assertEquals(blocks.map { it.start }, blocks.map { it.start }.sorted())
        val rev = blocks.first { it.kind == "revision" }
        assertEquals("18:00", rev.start)
        assertEquals("revise/session", rev.ref)
        assertTrue(blocks.any { it.kind == "telugu" && it.minutes == 15 })
        // nothing else overlaps the revision block
        val revStart = PlanBlocks.toMinutes(rev.start, 0)
        for (b in blocks.filter { it.kind != "revision" && it.kind != "brief" }) {
            val s = PlanBlocks.toMinutes(b.start, 0)
            assertFalse(s < revStart + rev.minutes && s + b.minutes > revStart)
        }
    }

    @Test
    fun fallbackHandlesRestDaysMissingBriefsAndNoCards() {
        assertTrue(PlanBlocks.fallback("d", 0.0, "07:00", "19:00", "18:00", 10, 15).isEmpty())
        val none = PlanBlocks.fallback("d", 3.0, null, null, "18:00", 0, 0)
        assertFalse(none.any { it.kind == "brief" || it.kind == "revision" })
        assertTrue(none.any { it.kind == "study" })
        assertEquals(180, PlanBlocks.totalMinutes(none))
    }

    @Test
    fun timeAndTextHelpers() {
        assertEquals("7:00 AM", PlanBlocks.displayTime("07:00"))
        assertEquals("12:30 PM", PlanBlocks.displayTime("12:30"))
        assertEquals("12:05 AM", PlanBlocks.displayTime("00:05"))
        assertEquals("6:00 PM", PlanBlocks.displayTime("18:00"))
        assertEquals("odd", PlanBlocks.displayTime("odd"))
        assertEquals("09:05", PlanBlocks.hhmm(545))
        assertEquals("23:59", PlanBlocks.hhmm(5000))
        assertEquals(90, PlanBlocks.toMinutes("01:30", 0))
        assertEquals(7, PlanBlocks.toMinutes("bad", 7))
        assertEquals("3 h 30 min", PlanBlocks.hoursText(210))
        assertEquals("45 min", PlanBlocks.hoursText(45))
        assertEquals("4 h", PlanBlocks.hoursText(240))
        assertEquals("Good morning", PlanBlocks.greeting(8))
        assertEquals("Good evening", PlanBlocks.greeting(19))
        assertEquals("Sunday, 20 September", PlanBlocks.longDate(LocalDate.of(2026, 9, 20)))
        assertEquals("open", PlanBlocks.statusOf(mapOf("a" to "done"), "b"))
        assertEquals("done", PlanBlocks.statusOf(mapOf("a" to "done"), "a"))
        assertEquals("Revise", PlanBlocks.startLabel("revision"))
        assertEquals("Start", PlanBlocks.startLabel("study"))
    }

    @Test
    fun studyHoursReadForgivinglyAndRoundTrip() {
        assertEquals(StudyPrefs.DEFAULT_HOURS, StudyPrefs.parseHours(null))
        val parsed = StudyPrefs.parseHours(obj("""{"mon":6,"tue":"x","sun":20,"wed":0.5}"""))
        assertEquals(6.0, parsed.getValue("mon"), 0.0)
        assertEquals(4.0, parsed.getValue("tue"), 0.0)
        assertEquals(StudyPrefs.MAX_HOURS, parsed.getValue("sun"), 0.0)
        assertEquals(0.5, parsed.getValue("wed"), 0.0)
        assertEquals(parsed, StudyPrefs.parseHours(StudyPrefs.hoursJson(parsed)))
        // 2026-09-21 is a Monday, 2026-09-27 a Sunday
        assertEquals(6.0, StudyPrefs.hoursFor(LocalDate.of(2026, 9, 21), parsed), 0.0)
        assertEquals(StudyPrefs.MAX_HOURS, StudyPrefs.hoursFor(LocalDate.of(2026, 9, 27), parsed), 0.0)
        assertEquals(27.0, StudyPrefs.weekHours(StudyPrefs.DEFAULT_HOURS), 0.0)
    }

    @Test
    fun priorityAndTeluguSettings() {
        assertEquals(0, StudyPrefs.priorityMode(StudyPrefs.parsePriority(null)))
        for (mode in 0..2) {
            assertEquals(mode, StudyPrefs.priorityMode(StudyPrefs.parsePriority(StudyPrefs.priorityJson(StudyPrefs.priorityFor(mode)))))
        }
        assertEquals(15, StudyPrefs.parseTelugu(null))
        assertEquals(30, StudyPrefs.parseTelugu(Json.parseToJsonElement("30")))
        assertEquals(120, StudyPrefs.parseTelugu(Json.parseToJsonElement("900")))
    }

    @Test
    fun examDatesAndCountdown() {
        val today = LocalDate.of(2026, 9, 20)
        assertEquals(LocalDate.of(2027, 2, 14), ExamDates.parseDay("2027-02-14T00:00:00Z"))
        assertNull(ExamDates.parseDay("soon"))
        assertNull(ExamDates.parseDay(null))
        assertEquals("2027-02-14T00:00:00Z", ExamDates.toStored(LocalDate.of(2027, 2, 14)))
        assertEquals(10, ExamDates.daysLeft("2026-09-30T00:00:00Z", today))
        assertEquals("10 days to go", ExamDates.countdownText("2026-09-30T00:00:00Z", today))
        assertEquals("Tomorrow", ExamDates.countdownText("2026-09-21T00:00:00Z", today))
        assertEquals("Today", ExamDates.countdownText("2026-09-20T00:00:00Z", today))
        assertEquals("Done", ExamDates.countdownText("2026-09-01T00:00:00Z", today))
        assertEquals("4 months to go", ExamDates.countdownText("2027-02-14T00:00:00Z", today))
        assertEquals("Date not announced", ExamDates.countdownText(null, today))
    }
}
