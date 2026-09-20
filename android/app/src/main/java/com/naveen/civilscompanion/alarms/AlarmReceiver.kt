package com.naveen.civilscompanion.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.naveen.civilscompanion.appEntryPoint
import com.naveen.civilscompanion.sync.SyncScheduler

/** Handles brief-time alarms, "remind me" alarms, and re-registering after reboot. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.appEntryPoint()
        if (!app.tokens().loggedIn.value) return
        when (intent.action) {
            ACTION_SLOT -> {
                SyncScheduler.syncNow(context)
                app.alarms().rescheduleAll() // sets the next occurrence
            }
            ACTION_REMIND_SET -> {
                val id = intent.getStringExtra(EXTRA_BRIEF_ID) ?: return
                app.notifier().cancel(id)
                app.alarms().scheduleReminder(
                    id, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_BODY).orEmpty(),
                )
            }
            ACTION_REMIND_FIRE -> {
                val id = intent.getStringExtra(EXTRA_BRIEF_ID) ?: return
                app.notifier().show(
                    id, intent.getStringExtra(EXTRA_TITLE).orEmpty(), intent.getStringExtra(EXTRA_BODY).orEmpty(),
                    alert = true,
                )
            }
            else -> { // boot completed, app updated, exact-alarm permission changed
                app.alarms().rescheduleAll()
                SyncScheduler.schedulePeriodic(context)
            }
        }
    }

    companion object {
        const val ACTION_SLOT = "com.naveen.civilscompanion.BRIEF_SLOT"
        const val ACTION_REMIND_SET = "com.naveen.civilscompanion.REMIND_SET"
        const val ACTION_REMIND_FIRE = "com.naveen.civilscompanion.REMIND_FIRE"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_BRIEF_ID = "brief_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
    }
}
