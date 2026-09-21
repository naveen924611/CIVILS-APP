package com.naveen.civilscompanion.goals

import com.naveen.civilscompanion.ui.goals.SiRules
import com.naveen.civilscompanion.ui.goals.SiRules.PetProfile
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiRulesTest {
    private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

    // ---------------------------------------------------------------- age

    @Test
    fun ageIsCompletedYearsOnTheReferenceDate() {
        assertEquals(21, SiRules.ageOn(d(2005, 7, 1)))
        assertEquals(20, SiRules.ageOn(d(2005, 7, 2)))
        assertEquals(27, SiRules.ageOn(d(1999, 7, 1)))
        assertEquals(26, SiRules.ageOn(d(1999, 7, 2)))
        assertEquals(30, SiRules.ageOn(d(1996, 1, 15), d(2026, 7, 1)))
    }

    @Test
    fun windowForOpenCategoryFollowsTheNotification() {
        // born_between 1999-07-02 to 2005-07-01
        assertTrue(SiRules.ageCheck(d(2005, 7, 1), "OC").eligible)
        val young = SiRules.ageCheck(d(2005, 7, 2), "OC")
        assertFalse(young.eligible)
        assertTrue(young.reason.startsWith("Too young"))
        assertTrue(SiRules.ageCheck(d(1999, 7, 2), "OC").eligible)
        val old = SiRules.ageCheck(d(1999, 7, 1), "OC")
        assertFalse(old.eligible)
        assertTrue(old.reason.startsWith("Too old"))
        assertEquals(0, old.relaxationYears)
    }

    @Test
    fun categoriesGetFiveYears() {
        for (cat in listOf("EWS", "BC", "SC", "ST")) {
            val ok = SiRules.ageCheck(d(1994, 7, 2), cat)
            assertTrue(cat, ok.eligible)
            assertEquals(5, ok.relaxationYears)
            assertFalse(cat, SiRules.ageCheck(d(1994, 7, 1), cat).eligible)
        }
        assertFalse(SiRules.ageCheck(d(1994, 7, 2), "OC").eligible)
        // the young end does not move
        assertFalse(SiRules.ageCheck(d(2005, 7, 2), "BC").eligible)
        assertTrue(SiRules.ageCheck(d(2005, 7, 1), "bc").eligible)
    }

    @Test
    fun exServicemanGetsThreePlusService() {
        val ok = SiRules.ageCheck(d(1992, 7, 2), "OC", exServiceman = true, serviceYears = 4)
        assertTrue(ok.eligible)
        assertEquals(7, ok.relaxationYears)
        assertFalse(SiRules.ageCheck(d(1992, 7, 1), "OC", exServiceman = true, serviceYears = 4).eligible)
    }

    @Test
    fun relaxationsAreNotAddedTogether() {
        // BC (5) and ex-serviceman with 0 years (3): the largest, 5, counts
        val a = SiRules.ageCheck(d(1994, 7, 2), "BC", exServiceman = true, serviceYears = 0)
        assertTrue(a.eligible)
        assertEquals(5, a.relaxationYears)
        assertFalse(SiRules.ageCheck(d(1994, 7, 1), "BC", exServiceman = true, serviceYears = 0).eligible)
        // BC (5) and NCC instructor with 3 years of service (6): 6 counts, not 11
        val b = SiRules.ageCheck(d(1993, 7, 2), "BC", nccInstructor = true, serviceYears = 3)
        assertTrue(b.eligible)
        assertEquals(6, b.relaxationYears)
        assertFalse(SiRules.ageCheck(d(1993, 7, 1), "BC", nccInstructor = true, serviceYears = 3).eligible)
        assertTrue(a.reason.contains("not added together"))
    }

    @Test
    fun governmentEmployeeGetsServiceUpToFive() {
        assertEquals(3, SiRules.ageCheck(d(2000, 1, 1), "OC", govtEmployee = true, serviceYears = 3).relaxationYears)
        assertEquals(5, SiRules.ageCheck(d(2000, 1, 1), "OC", govtEmployee = true, serviceYears = 9).relaxationYears)
        assertEquals(0, SiRules.ageCheck(d(2000, 1, 1), "OC", govtEmployee = true, serviceYears = 0).relaxationYears)
    }

    // ---------------------------------------------------------------- PMT

    @Test
    fun menHeightBoundary() {
        val pass = SiRules.pmtCheck(false, false, 167.6, 86.3, 91.3, null)
        assertTrue(pass.allPass)
        val fail = SiRules.pmtCheck(false, false, 167.5, 86.3, 91.3, null)
        assertFalse(fail.allPass)
        val height = fail.measures.first { it.name == "Height" }
        assertFalse(height.pass)
        assertEquals(0.1, height.shortfall, 1e-9)
        assertTrue(height.text().contains("short by 0.1 cm"))
    }

    @Test
    fun menChestAndExpansion() {
        val chestShort = SiRules.pmtCheck(false, false, 170.0, 86.2, 91.2, null)
        assertFalse(chestShort.measures.first { it.name == "Chest" }.pass)
        assertTrue(chestShort.measures.first { it.name == "Chest expansion" }.pass)
        val expansionShort = SiRules.pmtCheck(false, false, 170.0, 86.3, 91.2, null)
        val e = expansionShort.measures.first { it.name == "Chest expansion" }
        assertFalse(e.pass)
        assertEquals(0.1, e.shortfall, 1e-9)
        val exact = SiRules.pmtCheck(false, false, 170.0, 90.0, 95.0, null)
        assertTrue(exact.measures.first { it.name == "Chest expansion" }.pass)
    }

    @Test
    fun womenHeightAndWeight() {
        assertTrue(SiRules.pmtCheck(true, false, 152.5, null, null, 40.0).allPass)
        val light = SiRules.pmtCheck(true, false, 152.5, null, null, 39.9)
        assertFalse(light.allPass)
        assertEquals(0.1, light.measures.first { it.name == "Weight" }.shortfall, 1e-9)
        assertFalse(SiRules.pmtCheck(true, false, 152.4, null, null, 40.0).allPass)
        assertEquals(2, light.measures.size)
    }

    @Test
    fun aboStAgencyAreaRelaxations() {
        assertTrue(SiRules.pmtCheck(false, true, 160.0, 80.0, 83.0, null).allPass)
        assertFalse(SiRules.pmtCheck(false, true, 159.9, 80.0, 83.0, null).allPass)
        assertFalse(SiRules.pmtCheck(false, true, 160.0, 79.9, 83.0, null).allPass)
        assertFalse(SiRules.pmtCheck(false, true, 160.0, 80.0, 82.9, null).allPass)
        assertTrue(SiRules.pmtCheck(true, true, 150.0, null, null, 38.0).allPass)
        assertFalse(SiRules.pmtCheck(true, true, 149.9, null, null, 38.0).allPass)
        assertFalse(SiRules.pmtCheck(true, true, 150.0, null, null, 37.9).allPass)
        // the ordinary standard does not pass for the ABO-ST numbers
        assertFalse(SiRules.pmtCheck(false, false, 160.0, 80.0, 83.0, null).allPass)
    }

    @Test
    fun missingMeasuresAreNotAPass() {
        val r = SiRules.pmtCheck(false, false, null, null, null, null)
        assertFalse(r.allPass)
        assertTrue(r.measures.all { it.value == null && !it.pass })
        assertTrue(r.measures[0].text().contains("not entered"))
    }

    // ---------------------------------------------------------------- PET

    @Test
    fun runOf1600Boundaries() {
        assertTrue(SiRules.petPass(PetProfile.GENERAL, 480.0, 15.0).passed)
        assertFalse(SiRules.petPass(PetProfile.GENERAL, 481.0, 15.0).passed)
        assertTrue(SiRules.petPass(PetProfile.EX_SERVICEMAN, 570.0, 16.5).passed)
        assertFalse(SiRules.petPass(PetProfile.EX_SERVICEMAN, 571.0, 16.5).passed)
        assertTrue(SiRules.petPass(PetProfile.WOMEN, 630.0, 18.0).passed)
        assertFalse(SiRules.petPass(PetProfile.WOMEN, 631.0, 18.0).passed)
        assertEquals("10:30", SiRules.formatRun(630.0))
    }

    @Test
    fun longJumpBoundaries() {
        assertTrue(SiRules.petPass(PetProfile.GENERAL, 480.0, null, 3.80).passed)
        assertFalse(SiRules.petPass(PetProfile.GENERAL, 480.0, null, 3.79).passed)
        assertTrue(SiRules.petPass(PetProfile.EX_SERVICEMAN, 570.0, null, 3.65).passed)
        assertFalse(SiRules.petPass(PetProfile.EX_SERVICEMAN, 570.0, null, 3.64).passed)
        assertTrue(SiRules.petPass(PetProfile.WOMEN, 630.0, null, 2.75).passed)
        assertFalse(SiRules.petPass(PetProfile.WOMEN, 630.0, null, 2.74).passed)
    }

    @Test
    fun sprintBoundaries() {
        assertTrue(SiRules.petPass(PetProfile.GENERAL, 400.0, 15.0).passed)
        assertFalse(SiRules.petPass(PetProfile.GENERAL, 400.0, 15.1).passed)
        assertTrue(SiRules.petPass(PetProfile.EX_SERVICEMAN, 400.0, 16.5).passed)
        assertFalse(SiRules.petPass(PetProfile.EX_SERVICEMAN, 400.0, 16.6).passed)
        assertTrue(SiRules.petPass(PetProfile.WOMEN, 400.0, 18.0).passed)
        assertFalse(SiRules.petPass(PetProfile.WOMEN, 400.0, 18.1).passed)
    }

    @Test
    fun needsTheRunAndOneOfSprintOrJump() {
        val onlyRun = SiRules.petPass(PetProfile.GENERAL, 470.0)
        assertTrue(onlyRun.run1600Pass)
        assertNull(onlyRun.sprintPass)
        assertNull(onlyRun.longJumpPass)
        assertFalse(onlyRun.passed)
        assertEquals(1, onlyRun.missing.size)
        // fast sprint but slow run: fails
        val slowRun = SiRules.petPass(PetProfile.GENERAL, 500.0, 12.0, 4.5)
        assertFalse(slowRun.run1600Pass)
        assertEquals(true, slowRun.sprintPass)
        assertEquals(true, slowRun.longJumpPass)
        assertFalse(slowRun.passed)
        assertEquals(1, slowRun.missing.size)
        // sprint fails but jump passes: passes
        val mixed = SiRules.petPass(PetProfile.GENERAL, 470.0, 16.0, 3.90)
        assertEquals(false, mixed.sprintPass)
        assertTrue(mixed.passed)
        assertTrue(mixed.missing.isEmpty())
        // both fail
        val both = SiRules.petPass(PetProfile.GENERAL, 470.0, 16.0, 3.0)
        assertFalse(both.passed)
        assertEquals(1, both.missing.size)
    }

    @Test
    fun petProfileChoice() {
        assertEquals(PetProfile.WOMEN, SiRules.petProfileFor(true, false))
        assertEquals(PetProfile.WOMEN, SiRules.petProfileFor(true, true))
        assertEquals(PetProfile.EX_SERVICEMAN, SiRules.petProfileFor(false, true))
        assertEquals(PetProfile.GENERAL, SiRules.petProfileFor(false, false))
    }

    @Test
    fun formatAndParseRun() {
        assertEquals("8:00", SiRules.formatRun(480.0))
        assertEquals("8:05", SiRules.formatRun(485.0))
        assertEquals("9:30", SiRules.formatRun(570.0))
        assertEquals("8:05.5", SiRules.formatRun(485.5))
        assertEquals("8:00", SiRules.formatRun(479.96))
        assertEquals("0:45", SiRules.formatRun(45.0))
        assertEquals(485.0, SiRules.parseRun("8:05")!!, 1e-9)
        assertEquals(485.0, SiRules.parseRun(" 8:05 ")!!, 1e-9)
        assertEquals(485.0, SiRules.parseRun("485")!!, 1e-9)
        assertEquals(485.5, SiRules.parseRun("8:05.5")!!, 1e-9)
        assertEquals(480.0, SiRules.parseRun("8:00")!!, 1e-9)
        assertNull(SiRules.parseRun("8:60"))
        assertNull(SiRules.parseRun("abc"))
        assertNull(SiRules.parseRun(""))
        assertNull(SiRules.parseRun("-5"))
        assertNull(SiRules.parseRun("0"))
        assertNull(SiRules.parseRun("8:"))
        assertNull(SiRules.parseRun("8,05"))
    }

    // ---------------------------------------------------------------- Prelims and fee

    @Test
    fun prelimCutoffs() {
        assertEquals(40, SiRules.prelimCutoffPercent("OC"))
        assertEquals(40, SiRules.prelimCutoffPercent("EWS"))
        assertEquals(35, SiRules.prelimCutoffPercent("BC"))
        assertEquals(30, SiRules.prelimCutoffPercent("SC"))
        assertEquals(30, SiRules.prelimCutoffPercent("ST"))
        assertEquals(35, SiRules.prelimCutoffPercent(" bc "))
        assertEquals(40, SiRules.prelimCutoffPercent("something"))
    }

    @Test
    fun prelimPassNeedsEachPaper() {
        assertTrue(SiRules.prelimPassed("OC", 40.0, 40.0))
        assertFalse(SiRules.prelimPassed("OC", 39.9, 90.0))
        assertFalse(SiRules.prelimPassed("OC", 90.0, 39.9))
        assertTrue(SiRules.prelimPassed("EWS", 40.0, 41.0))
        assertTrue(SiRules.prelimPassed("BC", 35.0, 35.0))
        assertFalse(SiRules.prelimPassed("BC", 34.9, 60.0))
        assertTrue(SiRules.prelimPassed("SC", 30.0, 30.0))
        assertFalse(SiRules.prelimPassed("ST", 29.9, 30.0))
        assertTrue(SiRules.prelimPaperPassed("ST", 30.0))
    }

    @Test
    fun fees() {
        assertEquals(600, SiRules.feeFor("OC", true))
        assertEquals(600, SiRules.feeFor("BC", true))
        assertEquals(600, SiRules.feeFor("EWS", true))
        assertEquals(300, SiRules.feeFor("SC", true))
        assertEquals(300, SiRules.feeFor("ST", true))
        assertEquals(600, SiRules.feeFor("SC", false))
        assertEquals(600, SiRules.feeFor("OC", false))
    }
}
