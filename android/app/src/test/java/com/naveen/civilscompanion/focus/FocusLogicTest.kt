package com.naveen.civilscompanion.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLogicTest {
    private val t0 = 1_000_000L

    private fun started(style: String = "50+10", focus: Int = 50, rest: Int = 10) =
        FocusLogic.startFocus(FocusState(), t0, "id1", style, focus, rest, "Polity", "topic1", null)

    @Test fun styleLengths() {
        assertEquals(50 to 10, FocusLogic.lengths(FocusLogic.STYLE_50, 40, 10))
        assertEquals(25 to 5, FocusLogic.lengths(FocusLogic.STYLE_25, 40, 10))
        assertEquals(180 to 60, FocusLogic.lengths(FocusLogic.STYLE_CUSTOM, 400, 90))
        assertEquals(5 to 0, FocusLogic.lengths(FocusLogic.STYLE_CUSTOM, 1, -3))
    }

    @Test fun countdownAndProgress() {
        val s = started()
        assertEquals(FocusPhase.Focus, s.phase)
        assertEquals(50 * 60_000L, FocusLogic.remainingMs(s, t0))
        assertEquals(2_400_000L, FocusLogic.remainingMs(s, t0 + 600_000L))
        assertEquals(0.2f, FocusLogic.progress(s, t0 + 600_000L), 0.001f)
        assertEquals(0L, FocusLogic.remainingMs(s, t0 + 9_000_000L))
        assertEquals(0L, FocusLogic.remainingMs(FocusState(), t0))
        assertEquals(0f, FocusLogic.progress(FocusState(), t0), 0f)
    }

    @Test fun pauseAndResumeKeepTheWorkedTime() {
        val paused = FocusLogic.pause(started(), t0 + 600_000L)
        assertTrue(paused.paused)
        assertEquals(600_000L, paused.focusedMs)
        assertEquals(2_400_000L, FocusLogic.remainingMs(paused, t0 + 5_000_000L))
        assertEquals(600_000L, FocusLogic.focusedMsNow(paused, t0 + 5_000_000L))
        val resumed = FocusLogic.resume(paused, t0 + 900_000L)
        assertFalse(resumed.paused)
        assertEquals(t0 + 900_000L + 2_400_000L, resumed.phaseEndAt)
        assertEquals(700_000L, FocusLogic.focusedMsNow(resumed, t0 + 1_000_000L))
        assertTrue(FocusLogic.pause(paused, t0 + 700_000L) === paused)
    }

    @Test fun finishingAsksHowMuchWasDone() {
        val asking = FocusLogic.toAsking(started(), t0 + 3_000_000L)
        assertEquals(FocusPhase.Asking, asking.phase)
        assertEquals(3_000_000L, asking.focusedMs)
        assertEquals(50, FocusLogic.minutesOf(asking.focusedMs))
        val early = FocusLogic.toAsking(started(), t0 + 10 * 60_000L)
        assertEquals(10, FocusLogic.minutesOf(early.focusedMs))
        assertEquals(0L, FocusLogic.remainingMs(asking, t0 + 4_000_000L))
    }

    @Test fun minutesRounding() {
        assertEquals(0, FocusLogic.minutesOf(29_000L))
        assertEquals(1, FocusLogic.minutesOf(30_000L))
        assertEquals(2, FocusLogic.minutesOf(100_000L))
    }

    @Test fun afterTheAnswerABreakOrIdle() {
        val asking = FocusLogic.toAsking(started(), t0 + 3_000_000L)
        val rest = FocusLogic.afterAnswer(asking, t0 + 3_100_000L, tookFullTime = true)
        assertEquals(FocusPhase.Break, rest.phase)
        assertEquals(t0 + 3_100_000L + 600_000L, rest.phaseEndAt)
        assertEquals("", rest.sessionId)
        val none = FocusLogic.afterAnswer(asking, t0 + 3_100_000L, tookFullTime = false)
        assertEquals(FocusPhase.Idle, none.phase)
        val noBreakStyle = FocusLogic.afterAnswer(FocusLogic.toAsking(started("custom", 30, 0), t0 + 1_800_000L), t0, true)
        assertEquals(FocusPhase.Idle, noBreakStyle.phase)
    }

    @Test fun phaseOverHasOneSecondOfSlack() {
        val s = started()
        assertTrue(FocusLogic.isOver(s, s.phaseEndAt - 500L))
        assertFalse(FocusLogic.isOver(s, s.phaseEndAt - 1_500L))
        assertFalse(FocusLogic.isOver(FocusLogic.pause(s, t0 + 1000L), s.phaseEndAt + 10_000L))
        assertFalse(FocusLogic.isOver(FocusState(), 0L))
    }

    @Test fun clockText() {
        assertEquals("00:00", FocusLogic.clock(0))
        assertEquals("00:01", FocusLogic.clock(1))
        assertEquals("50:00", FocusLogic.clock(3_000_000L))
        assertEquals("1:00:00", FocusLogic.clock(3_600_000L))
        assertEquals("01:00", FocusLogic.clock(59_001L))
    }

    @Test fun minutesToday() {
        assertEquals(20, FocusLogic.minutesSince(listOf(100L, 5_000L, null), listOf(10, 20, 30), 1_000L))
        assertEquals(0, FocusLogic.minutesSince(emptyList(), emptyList(), 0L))
    }
}
