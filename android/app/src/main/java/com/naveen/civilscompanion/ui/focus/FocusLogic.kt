package com.naveen.civilscompanion.ui.focus

import kotlinx.serialization.Serializable

/** Idle: nothing running. Focus: working. Asking: the work part is over and the app asks how much was finished. Break: resting. */
enum class FocusPhase { Idle, Focus, Asking, Break }

/**
 * Everything the timer needs, kept as plain data so it survives the app being closed (it is saved on the tablet).
 * Times are epoch milliseconds from the tablet clock.
 */
@Serializable
data class FocusState(
    val phase: FocusPhase = FocusPhase.Idle,
    val style: String = FocusLogic.STYLE_50,
    val focusMinutes: Int = 50,
    val breakMinutes: Int = 10,
    val task: String = "",
    val topicId: String? = null,
    val blockId: String? = null,
    /** When the running phase ends (ignored while paused). */
    val phaseEndAt: Long = 0L,
    /** Time left when paused. */
    val remainingMs: Long = 0L,
    val paused: Boolean = false,
    /** When the session began (saved as FocusSession.started_at). */
    val startedAt: Long = 0L,
    /** Time really spent working so far (pauses and breaks not counted), in milliseconds. */
    val focusedMs: Long = 0L,
    /** When the current stretch of working began (0 while paused or not working). */
    val segmentStart: Long = 0L,
    val sessionId: String = "",
)

/** Rules of the Focus timer, with no Android in them so they can be tested. */
object FocusLogic {
    const val STYLE_50 = "50+10"
    const val STYLE_25 = "25+5"
    const val STYLE_CUSTOM = "custom"
    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 180

    /** (focus minutes, break minutes) for a style. Custom values are limited to sensible sizes. */
    fun lengths(style: String, customFocus: Int, customBreak: Int): Pair<Int, Int> = when (style) {
        STYLE_25 -> 25 to 5
        STYLE_CUSTOM -> customFocus.coerceIn(MIN_MINUTES, MAX_MINUTES) to customBreak.coerceIn(0, 60)
        else -> 50 to 10
    }

    /** Time left in the running phase. */
    fun remainingMs(s: FocusState, now: Long): Long = when {
        s.phase == FocusPhase.Idle || s.phase == FocusPhase.Asking -> 0L
        s.paused -> s.remainingMs
        else -> (s.phaseEndAt - now).coerceAtLeast(0L)
    }

    /** Working time so far, including the stretch that is running now. */
    fun focusedMsNow(s: FocusState, now: Long): Long {
        val running = if (s.phase == FocusPhase.Focus && !s.paused && s.segmentStart > 0) (now - s.segmentStart).coerceAtLeast(0L) else 0L
        return s.focusedMs + running
    }

    /** Whole minutes for the log; anything from 30 seconds counts as one minute. */
    fun minutesOf(ms: Long): Int = ((ms + 30_000L) / 60_000L).toInt()

    /** 0.0 to 1.0: how much of the running phase has passed. */
    fun progress(s: FocusState, now: Long): Float {
        val total = when (s.phase) {
            FocusPhase.Focus -> s.focusMinutes * 60_000L
            FocusPhase.Break -> s.breakMinutes * 60_000L
            else -> return 0f
        }
        if (total <= 0L) return 0f
        return (1f - remainingMs(s, now).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /** "49:05" (rounded up to the next second so it never shows 00:00 before the end). */
    fun clock(ms: Long): String {
        val totalSeconds = ((ms.coerceAtLeast(0L) + 999L) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    fun startFocus(
        old: FocusState, now: Long, id: String, style: String, focusMinutes: Int, breakMinutes: Int,
        task: String, topicId: String?, blockId: String?,
    ): FocusState = old.copy(
        phase = FocusPhase.Focus, style = style, focusMinutes = focusMinutes, breakMinutes = breakMinutes,
        task = task, topicId = topicId, blockId = blockId,
        phaseEndAt = now + focusMinutes * 60_000L, remainingMs = 0L, paused = false,
        startedAt = now, focusedMs = 0L, segmentStart = now, sessionId = id,
    )

    fun pause(s: FocusState, now: Long): FocusState {
        if (s.paused || (s.phase != FocusPhase.Focus && s.phase != FocusPhase.Break)) return s
        val left = remainingMs(s, now)
        return if (s.phase == FocusPhase.Focus) {
            s.copy(paused = true, remainingMs = left, focusedMs = focusedMsNow(s, now), segmentStart = 0L)
        } else {
            s.copy(paused = true, remainingMs = left)
        }
    }

    fun resume(s: FocusState, now: Long): FocusState {
        if (!s.paused) return s
        return s.copy(
            paused = false, phaseEndAt = now + s.remainingMs, remainingMs = 0L,
            segmentStart = if (s.phase == FocusPhase.Focus) now else 0L,
        )
    }

    /** The work part ended (the clock ran out, or the owner pressed Finish early): ask how much was finished. */
    fun toAsking(s: FocusState, now: Long): FocusState {
        if (s.phase != FocusPhase.Focus) return s
        return s.copy(
            phase = FocusPhase.Asking, focusedMs = focusedMsNow(s, now), segmentStart = 0L,
            paused = false, remainingMs = 0L, phaseEndAt = 0L,
        )
    }

    /** After the progress answer: a break when the whole work part was done and the style has one, otherwise idle. */
    fun afterAnswer(s: FocusState, now: Long, tookFullTime: Boolean): FocusState {
        val keep = s.copy(
            focusedMs = 0L, segmentStart = 0L, sessionId = "", task = "", topicId = null, blockId = null,
        )
        return if (tookFullTime && s.breakMinutes > 0) {
            keep.copy(phase = FocusPhase.Break, phaseEndAt = now + s.breakMinutes * 60_000L, paused = false, remainingMs = 0L)
        } else {
            keep.copy(phase = FocusPhase.Idle, phaseEndAt = 0L, paused = false, remainingMs = 0L)
        }
    }

    fun idle(s: FocusState): FocusState = s.copy(
        phase = FocusPhase.Idle, phaseEndAt = 0L, paused = false, remainingMs = 0L, focusedMs = 0L, segmentStart = 0L, sessionId = "",
    )

    /** True when the clock of a running (not paused) phase has reached its end. A second of slack covers alarm timing. */
    fun isOver(s: FocusState, now: Long): Boolean =
        (s.phase == FocusPhase.Focus || s.phase == FocusPhase.Break) && !s.paused && now >= s.phaseEndAt - 1000L

    /** Minutes worked in the sessions that started at or after [dayStart]. */
    fun minutesSince(startedAtMillis: List<Long?>, minutes: List<Int>, dayStart: Long): Int =
        startedAtMillis.indices.sumOf { i -> if ((startedAtMillis[i] ?: 0L) >= dayStart) minutes.getOrElse(i) { 0 } else 0 }

    /** The four answers offered when a session ends. */
    val PROGRESS_CHOICES = listOf(25, 50, 75, 100)
}
