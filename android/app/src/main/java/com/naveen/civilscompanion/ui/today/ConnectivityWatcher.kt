package com.naveen.civilscompanion.ui.today

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** true while the tablet has a network with internet. Used for the "Offline · using saved content" chip on Today. */
@Singleton
class ConnectivityWatcher @Inject constructor(@ApplicationContext private val context: Context) {

    private fun manager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun online(cm: ConnectivityManager): Boolean {
        return try {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: SecurityException) {
            true
        }
    }

    fun observe(): Flow<Boolean> = callbackFlow {
        val cm = manager()
        if (cm == null) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }
        trySend(online(cm))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(online(cm))
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }
        var registered = false
        try {
            cm.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: SecurityException) {
            trySend(true)
        }
        awaitClose {
            if (registered) {
                try {
                    cm.unregisterNetworkCallback(callback)
                } catch (e: IllegalArgumentException) {
                    // already unregistered
                }
            }
        }
    }.distinctUntilChanged()
}
