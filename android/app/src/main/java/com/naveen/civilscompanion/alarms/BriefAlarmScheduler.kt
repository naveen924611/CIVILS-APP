package com.naveen.civilscompanion.alarms

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.Prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side alarms at each brief time. They wake the app to fetch the brief even if the push message was
 * missed (for example the tablet was offline). Re-registered after every reboot and app update.
 */
@Singleton
class BriefAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: Prefs,
) {
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun rescheduleAll(now: ZonedDateTime = ZonedDateTime.now()) {
        cancelAll()
        for (slot in prefs.briefSettings.briefs) {
            val next = BriefTimes.nextOccurrence(slot, now) ?: continue
            setAlarm(slotIntent(slot.id), next.toInstant().toEpochMilli())
        }
    }

    fun cancelAll() {
        for (id in SLOT_IDS) alarms.cancel(slotIntent(id))
    }

    /** "Remind in 30 min" from the notification. */
    fun scheduleReminder(briefId: String, title: String, body: String, delayMinutes: Long = 30) {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_REMIND_FIRE)
            .putExtra(AlarmReceiver.EXTRA_BRIEF_ID, briefId)
            .putExtra(AlarmReceiver.EXTRA_TITLE, title)
            .putExtra(AlarmReceiver.EXTRA_BODY, body)
        val pi = PendingIntent.getBroadcast(context, briefId.hashCode(), intent, FLAGS)
        setAlarm(pi, System.currentTimeMillis() + delayMinutes * 60_000)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun setAlarm(pi: PendingIntent, atMillis: Long) {
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun slotIntent(slotId: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_SLOT)
            .putExtra(AlarmReceiver.EXTRA_SLOT, slotId)
        return PendingIntent.getBroadcast(context, 100 + SLOT_IDS.indexOf(slotId), intent, FLAGS)
    }

    companion object {
        val SLOT_IDS = listOf("morning", "evening", "extra1", "extra2")
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
