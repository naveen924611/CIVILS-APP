package com.naveen.civilscompanion.ui.focus

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Do Not Disturb during a focus session. Needs the "Do Not Disturb access" the owner grants in Android Settings.
 * Without it every call here does nothing and returns false, and the timer still works.
 */
@Singleton
class FocusDnd @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val p = context.getSharedPreferences("focus", Context.MODE_PRIVATE)

    fun hasAccess(): Boolean = try {
        nm.isNotificationPolicyAccessGranted
    } catch (e: RuntimeException) {
        false
    }

    /** True when Do Not Disturb is on right now (by us or by the owner). */
    fun isOn(): Boolean {
        val filter = try {
            nm.currentInterruptionFilter
        } catch (e: RuntimeException) {
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        return filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    /** Turns on "priority only" (calls and alarms still ring). Remembers what it was before. Returns true when it is on. */
    fun enable(): Boolean {
        if (!hasAccess()) return false
        return try {
            val before = nm.currentInterruptionFilter
            if (!p.contains(KEY_BEFORE)) p.edit().putInt(KEY_BEFORE, before).apply()
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Puts Do Not Disturb back the way it was before the session. */
    fun restore() {
        if (!p.contains(KEY_BEFORE)) return
        val before = p.getInt(KEY_BEFORE, NotificationManager.INTERRUPTION_FILTER_ALL)
        p.edit().remove(KEY_BEFORE).apply()
        if (!hasAccess()) return
        try {
            val target = if (before == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                before
            }
            nm.setInterruptionFilter(target)
        } catch (e: SecurityException) {
            // access was withdrawn meanwhile: nothing to restore
        }
    }

    fun accessSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val KEY_BEFORE = "dnd_before"
    }
}
