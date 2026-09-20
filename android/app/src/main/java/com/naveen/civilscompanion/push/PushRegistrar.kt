package com.naveen.civilscompanion.push

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.naveen.civilscompanion.data.auth.TokenStore
import com.naveen.civilscompanion.data.remote.DeviceApi
import com.naveen.civilscompanion.data.remote.dto.DeviceRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

/** Gives the server this tablet's push address. Does nothing until Firebase is set up. */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DeviceApi,
    private val tokens: TokenStore,
) {
    private val firebaseReady get() = FirebaseApp.getApps(context).isNotEmpty()

    suspend fun registerCurrentToken() {
        if (!firebaseReady) return
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        send(token)
    }

    suspend fun onNewToken(token: String) {
        if (tokens.loggedIn.value) send(token)
    }

    private suspend fun send(token: String) {
        runCatching { api.register(DeviceRequest(token, "${Build.MANUFACTURER} ${Build.MODEL}")) }
            .onFailure { Log.w("PushRegistrar", "Could not register push token: ${it.message}") }
    }
}
