package com.naveen.civilscompanion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class CivilsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // minSdk is 28, so notification channels always exist. More channels arrive in M2.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(GENERAL_CHANNEL, getString(R.string.channel_general), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val GENERAL_CHANNEL = "general"
    }
}
