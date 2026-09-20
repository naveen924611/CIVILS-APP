package com.naveen.civilscompanion.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.naveen.civilscompanion.AppLinks
import com.naveen.civilscompanion.CivilsApp
import com.naveen.civilscompanion.R
import com.naveen.civilscompanion.alarms.AlarmReceiver
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.decodeStrings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Shows the "Your morning brief is ready" notification with Play now / Read / Remind buttons (spec section 11). */
@Singleton
class BriefNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: Prefs,
    private val db: AppDatabase,
    private val json: Json,
) {
    fun notificationId(briefId: String) = briefId.hashCode()

    fun show(briefId: String, title: String, body: String, alert: Boolean) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val play = PendingIntent.getActivity(
            context, notificationId(briefId) + 1,
            AppLinks.activityIntent(context, AppLinks.ACTION_PLAY_BRIEF, briefId), FLAGS,
        )
        val read = PendingIntent.getActivity(
            context, notificationId(briefId) + 2,
            AppLinks.activityIntent(context, AppLinks.ACTION_OPEN_BRIEF, briefId), FLAGS,
        )
        val remind = PendingIntent.getBroadcast(
            context, notificationId(briefId) + 3,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REMIND_SET)
                .putExtra(AlarmReceiver.EXTRA_BRIEF_ID, briefId)
                .putExtra(AlarmReceiver.EXTRA_TITLE, title)
                .putExtra(AlarmReceiver.EXTRA_BODY, body),
            FLAGS,
        )
        val n = NotificationCompat.Builder(context, CivilsApp.BRIEFS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(read)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(!alert)
            .addAction(0, "Play now", play)
            .addAction(0, "Read", read)
            .addAction(0, "Remind in 30 min", remind)
            .build()
        try {
            nm.notify(notificationId(briefId), n)
        } catch (e: SecurityException) {
            // notification permission was turned off between the check and now
        }
    }

    /** Called with the push message text as soon as it arrives. */
    fun showFromPush(briefId: String?, title: String, body: String) {
        if (briefId == null) return
        if (!prefs.wasNotified(briefId)) {
            show(briefId, title, body, alert = true)
            prefs.markNotified(briefId)
        }
    }

    /** After a sync: notify for recent ready briefs we have not announced yet (a missed push), and
     *  quietly update an existing notification to say "downloaded for offline". */
    suspend fun notifyRecent(downloaded: Set<String>) {
        val since = System.currentTimeMillis() - RECENT_MS
        for (brief in db.briefs().readySince(since)) {
            val count = decodeStrings(json, brief.itemIdsJson).size
            val minutes = (brief.audioSecondsTotal + 30) / 60
            val isDownloaded = brief.id in downloaded
            val text = buildString {
                append("$count items")
                if (minutes > 0) append(" · $minutes min")
                if (isDownloaded) append(" · downloaded for offline")
            }
            val title = "Your ${label(brief.kind)} brief is ready"
            if (!prefs.wasNotified(brief.id)) {
                show(brief.id, title, text, alert = true)
                prefs.markNotified(brief.id)
            } else if (isDownloaded && isShowing(brief.id)) {
                show(brief.id, title, text, alert = false)
            }
        }
    }

    fun cancel(briefId: String) = NotificationManagerCompat.from(context).cancel(notificationId(briefId))

    private fun isShowing(briefId: String): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return nm.activeNotifications.any { it.id == notificationId(briefId) }
    }

    private fun label(kind: String) = when (kind) {
        "morning" -> "morning"
        "evening" -> "evening"
        else -> "extra"
    }

    companion object {
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val RECENT_MS = 3L * 3600 * 1000
    }
}
