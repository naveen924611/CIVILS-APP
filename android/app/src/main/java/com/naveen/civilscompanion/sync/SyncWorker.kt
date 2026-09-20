package com.naveen.civilscompanion.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.naveen.civilscompanion.appEntryPoint
import java.io.IOException
import java.util.concurrent.TimeUnit
import retrofit2.HttpException

/** Fetches new briefs and alerts, saves their audio, and shows the "brief ready" notification if needed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.appEntryPoint()
        if (!app.tokens().loggedIn.value) return Result.success()
        return try {
            app.sync().sync()
            runCatching {
                app.sync().refreshBriefSettings()
                app.alarms().rescheduleAll()
            }
            val saved = app.downloads().downloadRecent()
            app.notifier().notifyRecent(saved)
            Result.success()
        } catch (e: HttpException) {
            if (e.code() == 401) Result.success() else retryOrGiveUp()
        } catch (e: IOException) {
            retryOrGiveUp()
        } catch (e: kotlinx.serialization.SerializationException) {
            Result.failure()
        }
    }

    private fun retryOrGiveUp(): Result = if (runAttemptCount < 4) Result.retry() else Result.failure()
}

object SyncScheduler {
    private const val NOW = "sync-now"
    private const val PERIODIC = "sync-periodic"

    private fun constraints(context: Context): Constraints {
        val wifiOnly = context.appEntryPoint().prefs().wifiOnlyDownloads
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    /** Runs a sync as soon as the network allows. */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Safety net: also sync every few hours, whatever else happens. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints(context))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(NOW)
        wm.cancelUniqueWork(PERIODIC)
    }
}
