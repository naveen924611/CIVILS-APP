package com.naveen.civilscompanion.notify

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.naveen.civilscompanion.AppLinks
import com.naveen.civilscompanion.CivilsApp
import com.naveen.civilscompanion.R
import com.naveen.civilscompanion.alarms.BriefAlarmScheduler
import com.naveen.civilscompanion.data.model.WeeklyReport
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.today.PlanBlocks
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The day notifications of spec section 11: revision slot, evening day summary, Sunday weekly report, focus end.
 * Alarms are set with AlarmManager (like the brief alarms) and answered by [DayNotifierReceiver]. Each alarm sets the
 * next one after it fires; [rescheduleAll] is also called after a reboot, an app update and when Settings change.
 * Nothing here is shown during quiet hours (Settings, default 11 PM to 6 AM) except the focus-end message, which the
 * owner asked for by starting a timer.
 */
@Singleton
class DayNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kv: KvRepository,
    private val store: RecordStore,
    private val briefAlarms: BriefAlarmScheduler,
) {
    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    // ------------------------------------------------------------------ alarms

    fun rescheduleAll(nowMillis: Long = System.currentTimeMillis()) {
        for (code in listOf(REQ_REVISION, REQ_SUMMARY, REQ_WEEKLY)) alarmManager.cancel(alarmIntent(code))
        val zone = TimeUtil.india
        if (DayNotifyLogic.parseBool(kv.get(DayNotifyLogic.KEY_REVISION), true)) {
            val slot = (kv.get(DayNotifyLogic.KEY_SLOT) as? JsonPrimitive)?.content ?: "18:00"
            DayNotifyLogic.nextDaily(slot, nowMillis, zone)?.let { setAlarm(alarmIntent(REQ_REVISION), it) }
        }
        val summary = DayNotifyLogic.parseSummary(kv.get(DayNotifyLogic.KEY_SUMMARY))
        if (summary.enabled) {
            DayNotifyLogic.nextDaily(summary.time, nowMillis, zone)?.let { setAlarm(alarmIntent(REQ_SUMMARY), it) }
        }
        if (DayNotifyLogic.parseBool(kv.get(DayNotifyLogic.KEY_WEEKLY), true)) {
            DayNotifyLogic.nextSunday(DayNotifyLogic.WEEKLY_TIME, nowMillis, zone)?.let { setAlarm(alarmIntent(REQ_WEEKLY), it) }
        }
    }

    /** "Snooze 1 h" on the revision notification. */
    fun snoozeRevision(minutes: Long = 60) {
        setAlarm(alarmIntent(REQ_SNOOZE), System.currentTimeMillis() + minutes * 60_000)
    }

    fun scheduleFocusEnd(atMillis: Long) = setAlarm(alarmIntent(REQ_FOCUS), atMillis)

    fun cancelFocusEnd() = alarmManager.cancel(alarmIntent(REQ_FOCUS))

    @SuppressLint("ScheduleExactAlarm")
    private fun setAlarm(pi: PendingIntent, atMillis: Long) {
        try {
            if (briefAlarms.canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            // exact alarm permission was taken away a moment ago: the next reschedule will fall back to an inexact alarm
        }
    }

    private fun alarmIntent(code: Int): PendingIntent {
        val action = when (code) {
            REQ_REVISION, REQ_SNOOZE -> DayNotifierReceiver.ACTION_REVISION
            REQ_SUMMARY -> DayNotifierReceiver.ACTION_SUMMARY
            REQ_WEEKLY -> DayNotifierReceiver.ACTION_WEEKLY
            else -> DayNotifierReceiver.ACTION_FOCUS_END
        }
        val intent = Intent(context, DayNotifierReceiver::class.java).setAction(action)
        // A snooze must not restart the daily chain, so it says so.
        if (code == REQ_SNOOZE) intent.putExtra(DayNotifierReceiver.EXTRA_ONE_SHOT, true)
        return PendingIntent.getBroadcast(context, code, intent, FLAGS)
    }

    // ------------------------------------------------------------------ what is shown

    fun isQuietNow(nowMillis: Long = System.currentTimeMillis()): Boolean =
        DayNotifyLogic.isQuiet(DayNotifyLogic.parseQuiet(kv.get(DayNotifyLogic.KEY_QUIET)), DayNotifyLogic.minuteOfDay(nowMillis, TimeUtil.india))

    suspend fun postRevision() {
        if (isQuietNow() || !DayNotifyLogic.parseBool(kv.get(DayNotifyLogic.KEY_REVISION), true)) return
        val maxCards = (kv.get(DayNotifyLogic.KEY_MAX_CARDS) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 80
        val due = store.count(Tables.Cards, RecordQuery(numberMax = System.currentTimeMillis().toDouble())).coerceAtMost(maxCards)
        if (due <= 0) return
        val start = openRoute(REQ_REVISION + 10, Routes.REVISE_SESSION)
        val snooze = PendingIntent.getBroadcast(
            context, REQ_REVISION + 11,
            Intent(context, DayNotifierReceiver::class.java).setAction(DayNotifierReceiver.ACTION_SNOOZE), FLAGS,
        )
        show(
            NOTE_REVISION, CivilsApp.REVISION_CHANNEL, "Time to revise", DayNotifyLogic.revisionText(due), start,
            listOf("Start" to start, "Snooze 1 h" to snooze),
        )
    }

    suspend fun postSummary() {
        val setting = DayNotifyLogic.parseSummary(kv.get(DayNotifyLogic.KEY_SUMMARY))
        if (!setting.enabled || isQuietNow()) return
        val today = TimeUtil.today()
        val plan = store.list(Tables.DailyPlans, RecordQuery(k1 = today)).firstOrNull()
        val blocks = plan?.let { PlanBlocks.parseAll(it.blocks) }.orEmpty()
        val doneBlocks = blocks.filter { plan?.completion?.get(it.id)?.let { e -> (e as? JsonPrimitive)?.content } == PlanBlocks.DONE }
        val tomorrow = LocalDate.parse(today).plusDays(1).toString()
        val tomorrowReady = store.list(Tables.DailyPlans, RecordQuery(k1 = tomorrow)).isNotEmpty()
        val body = DayNotifyLogic.summaryText(
            planned = blocks.size, done = doneBlocks.size,
            plannedMinutes = PlanBlocks.totalMinutes(blocks), doneMinutes = PlanBlocks.totalMinutes(doneBlocks),
            tomorrowReady = tomorrowReady,
        )
        val open = openRoute(REQ_SUMMARY + 10, Routes.TODAY)
        show(NOTE_SUMMARY, CivilsApp.SUMMARY_CHANNEL, "Your day", body, open, listOf("Open" to open))
    }

    suspend fun postWeekly() {
        if (isQuietNow() || !DayNotifyLogic.parseBool(kv.get(DayNotifyLogic.KEY_WEEKLY), true)) return
        val latest: WeeklyReport = store.list(Tables.WeeklyReports, RecordQuery(order = Order.NewestFirst, limit = 1)).firstOrNull() ?: return
        val age = System.currentTimeMillis() - (TimeUtil.parse(latest.updatedAt) ?: 0L)
        if (latest.status != "ready" || age > 3L * 24 * 3600 * 1000) return
        val open = openRoute(REQ_WEEKLY + 10, Routes.REPORT)
        show(NOTE_WEEKLY, CivilsApp.GENERAL_CHANNEL, "Your weekly report is ready", "See how the week went and what changes next week.", open, listOf("Open" to open))
    }

    /** Session or break finished. The three buttons save how much was finished (the app screen offers 25 as well). */
    fun postFocus(title: String, body: String, askProgress: Boolean) {
        ensureFocusChannel()
        val open = openRoute(REQ_FOCUS + 10, Routes.FOCUS)
        val actions = if (askProgress) {
            listOf(50, 75, 100).map { pct ->
                "$pct%" to PendingIntent.getBroadcast(
                    context, REQ_FOCUS + 20 + pct,
                    Intent(context, DayNotifierReceiver::class.java).setAction(DayNotifierReceiver.ACTION_FOCUS_PCT)
                        .putExtra(DayNotifierReceiver.EXTRA_PCT, pct),
                    FLAGS,
                )
            }
        } else {
            emptyList()
        }
        show(NOTE_FOCUS, FOCUS_CHANNEL, title, body, open, actions)
    }

    fun cancelFocusNotification() = NotificationManagerCompat.from(context).cancel(NOTE_FOCUS)

    // ------------------------------------------------------------------ helpers

    private fun openRoute(code: Int, route: String): PendingIntent =
        PendingIntent.getActivity(context, code, AppLinks.activityIntent(context, AppLinks.ACTION_OPEN_ROUTE, route = route), FLAGS)

    private fun ensureFocusChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(FOCUS_CHANNEL, "Focus timer", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun show(
        id: Int,
        channel: String,
        title: String,
        body: String,
        content: PendingIntent,
        actions: List<Pair<String, PendingIntent>>,
    ) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
        actions.take(3).forEach { (label, pi) -> builder.addAction(0, label, pi) }
        try {
            nm.notify(id, builder.build())
        } catch (e: SecurityException) {
            // notification permission was turned off between the check and now
        }
    }

    companion object {
        const val FOCUS_CHANNEL = "focus"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val REQ_REVISION = 7100
        private const val REQ_SUMMARY = 7200
        private const val REQ_WEEKLY = 7300
        private const val REQ_SNOOZE = 7400
        private const val REQ_FOCUS = 7500
        private const val NOTE_REVISION = 7001
        private const val NOTE_SUMMARY = 7002
        private const val NOTE_WEEKLY = 7003
        private const val NOTE_FOCUS = 7004
    }
}
