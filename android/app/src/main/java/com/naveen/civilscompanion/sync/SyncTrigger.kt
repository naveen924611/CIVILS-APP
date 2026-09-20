package com.naveen.civilscompanion.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Something changed on this tablet, please push it soon." Waits a moment so a burst of edits becomes one sync. */
@Singleton
class SyncTrigger @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    @Synchronized
    fun request() {
        pending?.cancel()
        pending = scope.launch {
            delay(2_000)
            SyncScheduler.syncNow(context)
        }
    }
}
