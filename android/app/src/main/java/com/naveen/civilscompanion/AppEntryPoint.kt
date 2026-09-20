package com.naveen.civilscompanion

import android.content.Context
import com.naveen.civilscompanion.alarms.BriefAlarmScheduler
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.auth.TokenStore
import com.naveen.civilscompanion.data.repo.DownloadRepository
import com.naveen.civilscompanion.data.repo.SyncRepository
import com.naveen.civilscompanion.notify.BriefNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Lets workers and broadcast receivers (which Hilt does not create) reach the app's shared objects. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppEntryPoint {
    fun sync(): SyncRepository
    fun downloads(): DownloadRepository
    fun notifier(): BriefNotifier
    fun alarms(): BriefAlarmScheduler
    fun prefs(): Prefs
    fun tokens(): TokenStore
}

fun Context.appEntryPoint(): AppEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, AppEntryPoint::class.java)
