package com.naveen.civilscompanion.ui.library

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** Lets the worker reach the library repository (Hilt does not build workers for us). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LibraryEntryPoint {
    fun libraryRepository(): LibraryRepository
}

/** Sends camera photos that were taken offline as soon as the tablet has internet again. */
class ScanUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(applicationContext, LibraryEntryPoint::class.java).libraryRepository()
        val last = repo.flushScans()
        return if (last != null && last.offline) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "library-send-scans"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScanUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
