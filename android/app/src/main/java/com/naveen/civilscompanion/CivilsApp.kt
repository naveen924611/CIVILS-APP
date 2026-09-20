package com.naveen.civilscompanion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.naveen.civilscompanion.alarms.BriefAlarmScheduler
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.auth.TokenStore
import com.naveen.civilscompanion.data.local.DB_SCHEMA_VERSION
import com.naveen.civilscompanion.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class CivilsApp : Application() {

    @Inject lateinit var tokens: TokenStore
    @Inject lateinit var alarms: BriefAlarmScheduler
    @Inject lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannels()
        if (prefs.dbSchema != DB_SCHEMA_VERSION) {
            prefs.lastSync = null // the local database was rebuilt: fetch everything again
            prefs.dbSchema = DB_SCHEMA_VERSION
        }
        // Log in -> start syncing and set the brief alarms. Log out -> stop everything.
        scope.launch {
            tokens.loggedIn.collect { loggedIn ->
                if (loggedIn) {
                    SyncScheduler.schedulePeriodic(this@CivilsApp)
                    SyncScheduler.syncNow(this@CivilsApp)
                    alarms.rescheduleAll()
                } else {
                    SyncScheduler.cancelAll(this@CivilsApp)
                    alarms.cancelAll()
                }
            }
        }
    }

    /** minSdk is 28, so channels always exist. Separate channels let the owner mute each kind (spec section 11). */
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        fun channel(id: String, name: Int, importance: Int) =
            nm.createNotificationChannel(NotificationChannel(id, getString(name), importance))
        channel(GENERAL_CHANNEL, R.string.channel_general, NotificationManager.IMPORTANCE_DEFAULT)
        channel(BRIEFS_CHANNEL, R.string.channel_briefs, NotificationManager.IMPORTANCE_HIGH)
        channel(PLAYER_CHANNEL, R.string.channel_player, NotificationManager.IMPORTANCE_LOW)
        channel(ANSWERS_CHANNEL, R.string.channel_answers, NotificationManager.IMPORTANCE_DEFAULT)
        channel(REVISION_CHANNEL, R.string.channel_revision, NotificationManager.IMPORTANCE_DEFAULT)
        channel(SUMMARY_CHANNEL, R.string.channel_summary, NotificationManager.IMPORTANCE_LOW)
    }

    companion object {
        const val GENERAL_CHANNEL = "general"
        const val BRIEFS_CHANNEL = "briefs"
        const val PLAYER_CHANNEL = "player"
        const val ANSWERS_CHANNEL = "answers"
        const val REVISION_CHANNEL = "revision"
        const val SUMMARY_CHANNEL = "summary"
    }
}
