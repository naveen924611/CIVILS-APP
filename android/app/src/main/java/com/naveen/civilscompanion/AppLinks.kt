package com.naveen.civilscompanion

import android.content.Context
import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a notification button asks the app to do when it opens. */
data class PendingAction(val action: String, val briefId: String? = null)

object AppLinks {
    const val EXTRA_ACTION = "cc_action"
    const val EXTRA_BRIEF_ID = "cc_brief_id"
    const val ACTION_PLAY_BRIEF = "play_brief"
    const val ACTION_OPEN_BRIEF = "open_brief"
    const val ACTION_OPEN_ALERTS = "open_alerts"

    fun activityIntent(context: Context, action: String, briefId: String? = null): Intent =
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ACTION, action)
            .putExtra(EXTRA_BRIEF_ID, briefId)

    fun fromIntent(intent: Intent?): PendingAction? {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return null
        return PendingAction(action, intent.getStringExtra(EXTRA_BRIEF_ID))
    }
}

/** Carries "open this brief and play it" from a notification to the Briefs screen. */
@Singleton
class NavEvents @Inject constructor() {
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()

    fun post(action: PendingAction?) {
        if (action != null) _pending.value = action
    }

    fun consume() {
        _pending.value = null
    }
}
