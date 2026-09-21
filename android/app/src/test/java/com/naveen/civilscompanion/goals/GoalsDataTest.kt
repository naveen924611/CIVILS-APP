package com.naveen.civilscompanion.goals

import com.naveen.civilscompanion.ui.goals.GoalsData
import com.naveen.civilscompanion.ui.goals.GoalsData.PetEntry
import com.naveen.civilscompanion.ui.goals.GoalsData.PmtInput
import com.naveen.civilscompanion.ui.goals.GoalsData.SiProfile
import com.naveen.civilscompanion.ui.goals.SiRules
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalsDataTest {
    private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

    // ---------------------------------------------------------------- countdowns

    @Test
    fun daysAndWindowText() {
        val open = GoalsData.GROUP1_OPENS
        val close = GoalsData.GROUP1_CLOSES
        assertEquals(d(2026, 10, 6), open)
        assertEquals(d(2026, 10, 27), close)
        assertEquals(15, GoalsData.daysUntil(open, d(2026, 9, 21)))
        assertEquals(-1, GoalsData.daysUntil(open, d(2026, 10, 7)))
        assertEquals("Opens in 15 days", GoalsData.windowText(open, close, d(2026, 9, 21)))
        assertEquals("Opens in 1 day", GoalsData.windowText(open, close, d(2026, 10, 5)))
        assertEquals("Open now. Closes in 21 days", GoalsData.windowText(open, close, d(2026, 10, 6)))
        assertEquals("Open now. Closes in 1 day", GoalsData.windowText(open, close, d(2026, 10, 26)))
        assertEquals("Open now. Closes today at 11:59 PM", GoalsData.windowText(open, close, d(2026, 10, 27)))
        assertEquals("Closed", GoalsData.windowText(open, close, d(2026, 10, 28)))
    }

    @Test
    fun dueText() {
        val due = GoalsData.DETAILED_NOTIFICATION_DUE
        assertEquals("Due in 15 days", GoalsData.dueText(due, d(2026, 9, 21)))
        assertEquals("Due today", GoalsData.dueText(due, d(2026, 10, 6)))
        assertTrue(GoalsData.dueText(due, d(2026, 10, 7)).startsWith("The due date has passed"))
    }

    // ---------------------------------------------------------------- applied, profile

    @Test
    fun appliedDefaultsAndRoundTrip() {
        val none = GoalsData.parseApplied(null)
        assertEquals(false, none[GoalsData.APPLIED_GROUP1])
        assertEquals(false, none[GoalsData.APPLIED_SI])
        val json = GoalsData.appliedJson(mapOf(GoalsData.APPLIED_GROUP1 to true))
        val back = GoalsData.parseApplied(json)
        assertEquals(true, back[GoalsData.APPLIED_GROUP1])
        assertEquals(false, back[GoalsData.APPLIED_SI])
        assertEquals("{\"appsc-g1\":true,\"slprb-si\":false}", json.toString())
        assertEquals(false, GoalsData.parseApplied(Json.parseToJsonElement("[1,2]"))[GoalsData.APPLIED_SI])
    }

    @Test
    fun profileRoundTrip() {
        val p = SiProfile(
            female = true, category = "BC", dob = d(2001, 3, 9), local = false, exServiceman = true, serviceYears = 4,
            govtEmployee = true, nccInstructor = true, aboSt = true, degreeDone = true,
        )
        assertEquals(p, GoalsData.parseProfile(GoalsData.profileJson(p)))
        val plain = SiProfile()
        assertEquals(plain, GoalsData.parseProfile(GoalsData.profileJson(plain)))
        assertNull(GoalsData.parseProfile(GoalsData.profileJson(plain)).dob)
    }

    @Test
    fun profileParsingIsForgiving() {
        assertEquals(SiProfile(), GoalsData.parseProfile(null))
        assertEquals(SiProfile(), GoalsData.parseProfile(Json.parseToJsonElement("\"junk\"")))
        assertEquals(SiProfile(), GoalsData.parseProfile(Json.parseToJsonElement("{}")))
        val odd = GoalsData.parseProfile(
            Json.parseToJsonElement("""{"category":"xyz","dob":"not a date","service_years":"7","local":"false","gender":"Female"}"""),
        )
        assertEquals("OC", odd.category)
        assertNull(odd.dob)
        assertEquals(7, odd.serviceYears)
        assertFalse(odd.local)
        assertTrue(odd.female)
        val lower = GoalsData.parseProfile(Json.parseToJsonElement("""{"category":"sc","service_years":900}"""))
        assertEquals("SC", lower.category)
        assertEquals(40, lower.serviceYears)
    }

    @Test
    fun ageResultNeedsADateOfBirth() {
        assertNull(GoalsData.ageResult(SiProfile()))
        val r = GoalsData.ageResult(SiProfile(category = "BC", dob = d(1994, 7, 2)))
        assertNotNull(r)
        assertTrue(r!!.eligible)
        assertFalse(GoalsData.ageResult(SiProfile(category = "OC", dob = d(1994, 7, 2)))!!.eligible)
        assertEquals(SiRules.PetProfile.WOMEN, GoalsData.petProfile(SiProfile(female = true)))
        assertEquals(d(2026, 2, 28), GoalsData.dateOrNull(2026, 2, 28))
        assertNull(GoalsData.dateOrNull(2026, 2, 30))
        assertNull(GoalsData.dateOrNull(2026, 13, 1))
    }

    // ---------------------------------------------------------------- checklist, PMT

    @Test
    fun checklistRoundTripAndCount() {
        val items = GoalsData.checklistItems(600)
        assertTrue(items.any { it.key == "fee" && it.label.contains("Rs 600") })
        assertTrue(items.all { it.source.startsWith("Notification:") })
        assertEquals(items.size, items.map { it.key }.toSet().size)
        val m = mapOf("degree" to true, "fee" to false)
        val back = GoalsData.parseChecklist(GoalsData.checklistJson(m))
        assertEquals(m, back)
        assertEquals(1, GoalsData.checklistDone(items, back))
        assertTrue(GoalsData.parseChecklist(null).isEmpty())
        assertEquals(mapOf("a" to true), GoalsData.parseChecklist(Json.parseToJsonElement("""{"a":true,"b":"x","c":[1]}""")))
    }

    @Test
    fun pmtRoundTripAndNumbers() {
        val p = PmtInput(heightCm = 167.6, chestCm = 86.3, chestExpandedCm = 91.3, weightKg = null)
        assertEquals(p, GoalsData.parsePmt(GoalsData.pmtJson(p)))
        assertEquals(PmtInput(), GoalsData.parsePmt(null))
        assertEquals(167.6, GoalsData.parseNumber("167.6")!!, 1e-9)
        assertEquals(167.6, GoalsData.parseNumber(" 167,6 ")!!, 1e-9)
        assertNull(GoalsData.parseNumber(""))
        assertNull(GoalsData.parseNumber("abc"))
        assertNull(GoalsData.parseNumber("0"))
        assertNull(GoalsData.parseNumber("-3"))
    }

    // ---------------------------------------------------------------- PET log

    private fun entry(id: String, date: String, run: Double, sprint: Double? = null, jump: Double? = null) =
        PetEntry(id, date, run, sprint, jump)

    @Test
    fun petLogRoundTripAndForgivingRead() {
        val list = listOf(entry("a", "2026-09-20", 485.0, 14.5, 3.85), entry("b", "2026-09-21", 470.0))
        assertEquals(list, GoalsData.parsePetLog(GoalsData.petLogJson(list)))
        assertTrue(GoalsData.parsePetLog(null).isEmpty())
        val messy = Json.parseToJsonElement(
            """[{"id":"x","date":"2026-09-01","run1600":500},"junk",{"id":"","date":"2026-09-01","run1600":500},{"id":"y","date":"2026-09-02"},{"id":"z","date":"2026-09-03","run1600":-4}]""",
        )
        assertEquals(listOf(entry("x", "2026-09-01", 500.0)), GoalsData.parsePetLog(messy))
    }

    @Test
    fun petLogKeepsTheLast200() {
        var list = emptyList<PetEntry>()
        for (i in 1..205) list = GoalsData.addPetEntry(list, entry("id$i", "2026-01-01", 480.0))
        assertEquals(200, list.size)
        assertEquals("id6", list.first().id)
        assertEquals("id205", list.last().id)
        assertEquals(199, GoalsData.removePetEntry(list, "id10").size)
        assertEquals(200, GoalsData.removePetEntry(list, "nope").size)
    }

    @Test
    fun newestFirstAndBests() {
        val list = listOf(
            entry("a", "2026-09-19", 500.0, 15.5, 3.50),
            entry("b", "2026-09-21", 480.0, null, 3.90),
            entry("c", "2026-09-21", 470.0, 14.0, null),
            entry("d", "2026-09-20", 490.0),
        )
        assertEquals(listOf("c", "b", "d", "a"), GoalsData.newestFirst(list).map { it.id })
        val best = GoalsData.bests(list)
        assertEquals(470.0, best.run1600!!, 1e-9)
        assertEquals(14.0, best.sprint100!!, 1e-9)
        assertEquals(3.90, best.longJump!!, 1e-9)
        val none = GoalsData.bests(emptyList())
        assertNull(none.run1600)
        assertNull(none.sprint100)
        assertNull(none.longJump)
        assertEquals(500.0, GoalsData.bests(listOf(entry("q", "2026-01-01", 500.0))).run1600!!, 1e-9)
        assertNull(GoalsData.bests(listOf(entry("q", "2026-01-01", 500.0))).sprint100)
    }

    @Test
    fun buildEntryReadsTheForm() {
        val ok = GoalsData.buildEntry("id1", "2026-09-21", "8:05", "14,5", "3.85")
        assertNull(ok.error)
        assertEquals(entry("id1", "2026-09-21", 485.0, 14.5, 3.85), ok.entry)
        val onlyRun = GoalsData.buildEntry("id2", "2026-09-21", "485", "", "")
        assertEquals(entry("id2", "2026-09-21", 485.0), onlyRun.entry)
        assertNotNull(GoalsData.buildEntry("i", "21-09-2026", "8:05", "", "").error)
        assertNotNull(GoalsData.buildEntry("i", "2026-09-21", "", "", "").error)
        assertNotNull(GoalsData.buildEntry("i", "2026-09-21", "8", "", "").error) // 8 seconds is not a 1600 m time
        assertNotNull(GoalsData.buildEntry("i", "2026-09-21", "8:05", "fast", "").error)
        assertNotNull(GoalsData.buildEntry("i", "2026-09-21", "8:05", "", "38").error)
        assertNull(GoalsData.buildEntry("i", "2026-09-21", "8:05", "", "38").entry)
    }

    @Test
    fun petResultAndSummary() {
        val e = entry("a", "2026-09-21", 480.0, 15.1, 3.80)
        val r = GoalsData.petResult(SiRules.PetProfile.GENERAL, e)
        assertTrue(r.run1600Pass)
        assertEquals(false, r.sprintPass)
        assertEquals(true, r.longJumpPass)
        assertTrue(r.passed)
        assertEquals("1600 m 8:00, 100 m 15.1 s, jump 3.80 m", GoalsData.petSummary(e))
        assertEquals("1600 m 8:05", GoalsData.petSummary(entry("b", "2026-09-21", 485.0)))
    }

    @Test
    fun marksParsing() {
        assertEquals(40.0, GoalsData.parseMarks("40")!!, 1e-9)
        assertEquals(0.0, GoalsData.parseMarks("0")!!, 1e-9)
        assertEquals(100.0, GoalsData.parseMarks("100")!!, 1e-9)
        assertEquals(37.5, GoalsData.parseMarks("37,5")!!, 1e-9)
        assertNull(GoalsData.parseMarks("101"))
        assertNull(GoalsData.parseMarks("-1"))
        assertNull(GoalsData.parseMarks(""))
    }

    @Test
    fun dateOfBirthFromThreeBoxes() {
        assertEquals(d(2001, 3, 9), GoalsData.dobFromText("9", "3", "2001"))
        assertEquals(d(2001, 3, 9), GoalsData.dobFromText(" 09 ", "03", " 2001"))
        assertNull(GoalsData.dobFromText("31", "2", "2001"))
        assertNull(GoalsData.dobFromText("", "3", "2001"))
        assertNull(GoalsData.dobFromText("9", "3", "200"))
        assertNull(GoalsData.dobFromText("9", "13", "2001"))
        assertNull(GoalsData.dobFromText("x", "3", "2001"))
    }

    @Test
    fun numberTextAndStandardText() {
        assertEquals("", GoalsData.numberText(null))
        assertEquals("167", GoalsData.numberText(167.0))
        assertEquals("167.6", GoalsData.numberText(167.6))
        assertEquals("General", GoalsData.petProfileName(SiRules.PetProfile.GENERAL))
        assertEquals(
            "1600 m in 8:00 or faster, and one of: 100 m in 15 s or faster, long jump 3.80 m or more",
            GoalsData.petStandardText(SiRules.PetProfile.GENERAL),
        )
        assertEquals(
            "1600 m in 10:30 or faster, and one of: 100 m in 18 s or faster, long jump 2.75 m or more",
            GoalsData.petStandardText(SiRules.PetProfile.WOMEN),
        )
    }
}
