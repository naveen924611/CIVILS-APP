package com.naveen.civilscompanion.push

import com.google.firebase.messaging.FirebaseMessagingService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CivilsMessagingService : FirebaseMessagingService() {

    @Inject lateinit var registrar: PushRegistrar
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { registrar.onNewToken(token) }
    }
    // Messages that carry a "notification" block are shown by Firebase itself.
    // Data messages (brief ready, answers ready) are handled from milestone M2 onwards.
}
