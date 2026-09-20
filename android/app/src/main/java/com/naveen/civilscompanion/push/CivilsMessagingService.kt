package com.naveen.civilscompanion.push

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.naveen.civilscompanion.AppLinks
import com.naveen.civilscompanion.CivilsApp
import com.naveen.civilscompanion.R
import com.naveen.civilscompanion.notify.BriefNotifier
import com.naveen.civilscompanion.sync.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CivilsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registrar: PushRegistrar
    @Inject lateinit var notifier: BriefNotifier
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { registrar.onNewToken(token) }
    }

    /**
     * Messages with a "notification" block (like the test push) are shown by Firebase itself.
     * Silent data messages arrive here: the app builds its own notification (with buttons) and syncs.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"].orEmpty()
        val body = data["body"].orEmpty()
        when (data["type"]) {
            "brief_ready" -> notifier.showFromPush(data["brief_id"], title.ifBlank { "Your brief is ready" }, body)
            "brief_failed" -> showPlain(title, body)
        }
        if (data["type"] != null) SyncScheduler.syncNow(applicationContext)
    }

    private fun showPlain(title: String, body: String) {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val open = android.app.PendingIntent.getActivity(
            this, 7, AppLinks.activityIntent(this, AppLinks.ACTION_OPEN_ALERTS),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CivilsApp.GENERAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(title.hashCode(), n)
        } catch (e: SecurityException) {
            // notification permission was turned off; nothing to show
        }
    }
}
