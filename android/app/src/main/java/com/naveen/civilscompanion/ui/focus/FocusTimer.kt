package com.naveen.civilscompanion.ui.focus

import android.content.Context
import com.naveen.civilscompanion.data.model.FocusSession
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.notify.DayNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The one focus timer of the app. It lives outside any screen, so it keeps counting while the owner opens notes or the
 * reader, and it is saved on the tablet so a closed app does not lose the session. The end of each phase is also set as an
 * alarm, so the "session done" message arrives even with the screen off.
 * When a session ends the owner says how much was finished; that is saved as a FocusSession row (it syncs to the server,
 * whose planner uses it as pace data).
 */
@Singleton
class FocusTimer @Inject constructor(
    @ApplicationContext context: Context,
    private val store: RecordStore,
    private val notifier: DayNotifier,
    private val dnd: FocusDnd,
) {
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<FocusState> = _state.asStateFlow()

    /** Set when Do Not Disturb could not be switched on for the running session (no access), so the screen can say so. */
    private val _dndOn = MutableStateFlow(false)
    val dndOn: StateFlow<Boolean> = _dndOn.asStateFlow()

    private fun load(): FocusState =
        prefs.getString(KEY_STATE, null)?.let { runCatching { json.decodeFromString(FocusState.serializer(), it) }.getOrNull() }
            ?: FocusState()

    private fun set(next: FocusState) {
        _state.value = next
        prefs.edit().putString(KEY_STATE, json.encodeToString(FocusState.serializer(), next)).apply()
    }

    /** Makes the screen and the alarm agree with the saved state after the app was restarted. */
    fun resync(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val s = _state.value
            if (FocusLogic.isOver(s, now)) {
                onPhaseEndedLocked(now)
            } else if ((s.phase == FocusPhase.Focus || s.phase == FocusPhase.Break) && !s.paused) {
                notifier.scheduleFocusEnd(s.phaseEndAt)
            }
            _dndOn.value = s.phase == FocusPhase.Focus && dnd.isOn()
        }
    }

    fun start(style: String, focusMinutes: Int, breakMinutes: Int, task: String, topicId: String?, blockId: String?) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (_state.value.phase == FocusPhase.Focus || _state.value.phase == FocusPhase.Asking) return
            notifier.cancelFocusNotification()
            val next = FocusLogic.startFocus(
                _state.value, now, TimeUtil.newId(), style, focusMinutes, breakMinutes, task.trim(), topicId, blockId,
            )
            set(next)
            notifier.scheduleFocusEnd(next.phaseEndAt)
            _dndOn.value = dnd.enable()
        }
    }

    fun pause() {
        synchronized(lock) {
            val next = FocusLogic.pause(_state.value, System.currentTimeMillis())
            if (next !== _state.value) {
                set(next)
                notifier.cancelFocusEnd()
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            val next = FocusLogic.resume(_state.value, System.currentTimeMillis())
            if (next !== _state.value) {
                set(next)
                notifier.scheduleFocusEnd(next.phaseEndAt)
            }
        }
    }

    /** "Finish early": stop working now and ask how much was finished. */
    fun finishEarly() {
        synchronized(lock) {
            if (_state.value.phase == FocusPhase.Focus) toAskingLocked(System.currentTimeMillis(), ended = false)
        }
    }

    /** Called by the screen every second and by the alarm. Does nothing unless the running phase has really ended. */
    fun onPhaseEnded(now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            if (FocusLogic.isOver(_state.value, now)) onPhaseEndedLocked(now)
        }
    }

    private fun onPhaseEndedLocked(now: Long) {
        val s = _state.value
        when (s.phase) {
            FocusPhase.Focus -> toAskingLocked(now, ended = true)
            FocusPhase.Break -> {
                set(FocusLogic.idle(s))
                notifier.postFocus("Break over", "Ready for the next session when you are.", askProgress = false)
            }
            else -> Unit
        }
    }

    private fun toAskingLocked(now: Long, ended: Boolean) {
        val s = _state.value
        set(FocusLogic.toAsking(s, now))
        notifier.cancelFocusEnd()
        dnd.restore()
        _dndOn.value = false
        if (ended) {
            val what = s.task.ifBlank { "your session" }
            notifier.postFocus("Session done", "How much of $what did you finish?", askProgress = true)
        }
    }

    private class Answered(val before: FocusState, val minutes: Int)

    private fun applyAnswer(): Answered? {
        synchronized(lock) {
            val before = _state.value
            if (before.phase != FocusPhase.Asking) return null
            val minutes = FocusLogic.minutesOf(before.focusedMs)
            val tookFullTime = before.focusedMs >= before.focusMinutes * 60_000L - 30_000L
            set(FocusLogic.afterAnswer(before, System.currentTimeMillis(), tookFullTime))
            val after = _state.value
            if (after.phase == FocusPhase.Break) notifier.scheduleFocusEnd(after.phaseEndAt)
            return Answered(before, minutes)
        }
    }

    /** The owner's answer (25, 50, 75 or 100 percent). Saves the session and starts the break, if there is one. */
    suspend fun answer(percent: Int) {
        val done = applyAnswer() ?: return
        notifier.cancelFocusNotification()
        if (done.minutes > 0) {
            val before = done.before
            store.save(
                Tables.FocusSessions,
                FocusSession(
                    id = before.sessionId.ifBlank { TimeUtil.newId() },
                    topicId = before.topicId,
                    blockId = before.blockId,
                    startedAt = TimeUtil.toIso(before.startedAt),
                    minutes = done.minutes,
                    completionPct = percent.coerceIn(0, 100),
                    style = before.style,
                ),
            )
        }
    }

    /** Skip the rest of a break. */
    fun skipBreak() {
        synchronized(lock) {
            val s = _state.value
            if (s.phase == FocusPhase.Break) {
                set(FocusLogic.idle(s))
                notifier.cancelFocusEnd()
            }
        }
    }

    /** Throws the running session away without saving it (for example started by mistake). */
    fun cancelSession() {
        synchronized(lock) {
            set(FocusLogic.idle(_state.value))
            notifier.cancelFocusEnd()
            notifier.cancelFocusNotification()
            dnd.restore()
            _dndOn.value = false
        }
    }

    private companion object {
        const val KEY_STATE = "state"
    }
}
