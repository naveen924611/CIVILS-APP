package com.naveen.civilscompanion.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.naveen.civilscompanion.data.auth.TokenStore
import com.naveen.civilscompanion.ui.focus.FocusTimer
import com.naveen.civilscompanion.widget.WidgetUpdater
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Objects this receiver needs (Hilt does not create receivers). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DayNotifierEntryPoint {
    fun dayNotifier(): DayNotifier
    fun focusTimer(): FocusTimer
    fun tokens(): TokenStore
}

/**
 * Answers the alarms set by [DayNotifier] and the buttons on its notifications, and sets the alarms again after a reboot
 * or an app update (the brief alarms have their own receiver).
 */
class DayNotifierReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handle(app, intent)
            } catch (e: Exception) {
                // A notification problem must never crash the app in the background.
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val ep = EntryPointAccessors.fromApplication(context, DayNotifierEntryPoint::class.java)
        if (!ep.tokens().loggedIn.value) return
        val notifier = ep.dayNotifier()
        val oneShot = intent.getBooleanExtra(EXTRA_ONE_SHOT, false)
        when (intent.action) {
            ACTION_REVISION -> {
                notifier.postRevision()
                if (!oneShot) notifier.rescheduleAll()
            }
            ACTION_SUMMARY -> {
                notifier.postSummary()
                notifier.rescheduleAll()
            }
            ACTION_WEEKLY -> {
                notifier.postWeekly()
                notifier.rescheduleAll()
            }
            ACTION_SNOOZE -> notifier.snoozeRevision(60)
            ACTION_FOCUS_END -> ep.focusTimer().onPhaseEnded()
            ACTION_FOCUS_PCT -> ep.focusTimer().answer(intent.getIntExtra(EXTRA_PCT, 100))
            else -> { // boot completed, app updated
                ep.focusTimer().resync()
                notifier.rescheduleAll()
            }
        }
        WidgetUpdater.refresh(context)
    }

    companion object {
        const val ACTION_REVISION = "com.naveen.civilscompanion.DAY_REVISION"
        const val ACTION_SUMMARY = "com.naveen.civilscompanion.DAY_SUMMARY"
        const val ACTION_WEEKLY = "com.naveen.civilscompanion.DAY_WEEKLY"
        const val ACTION_SNOOZE = "com.naveen.civilscompanion.DAY_SNOOZE"
        const val ACTION_FOCUS_END = "com.naveen.civilscompanion.FOCUS_END"
        const val ACTION_FOCUS_PCT = "com.naveen.civilscompanion.FOCUS_PCT"
        const val EXTRA_ONE_SHOT = "one_shot"
        const val EXTRA_PCT = "pct"
    }
}
