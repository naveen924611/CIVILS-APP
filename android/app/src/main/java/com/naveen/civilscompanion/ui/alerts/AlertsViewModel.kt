package com.naveen.civilscompanion.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.AppLinks
import com.naveen.civilscompanion.NavEvents
import com.naveen.civilscompanion.PendingAction
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.payloadValue
import com.naveen.civilscompanion.data.repo.SyncRepository
import com.naveen.civilscompanion.ui.briefs.BriefFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class AlertUi(
    val id: String,
    val kind: String,
    val whenText: String,
    val title: String,
    val body: String,
    val unread: Boolean,
    val briefId: String?,
)

@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val db: AppDatabase,
    private val sync: SyncRepository,
    private val navEvents: NavEvents,
    private val json: Json,
) : ViewModel() {

    val alerts: StateFlow<List<AlertUi>> = db.alerts().observeAll()
        .map { list ->
            list.map {
                AlertUi(
                    id = it.id,
                    kind = it.kind,
                    whenText = "${BriefFormat.dayText(it.createdAt)} · ${BriefFormat.timeText(it.createdAt)}",
                    title = it.title,
                    body = it.body,
                    unread = !it.read,
                    briefId = payloadValue(json, it.payloadJson, "brief_id"),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { runCatching { sync.pull() } }
    }

    fun markAllRead() {
        viewModelScope.launch { sync.markAllAlertsRead() }
    }

    fun markRead(id: String) {
        viewModelScope.launch { sync.markAlertRead(id) }
    }

    /** "Play now" / "Read" on a brief alert: hand over to the Briefs screen. */
    fun openBrief(alert: AlertUi, play: Boolean) {
        markRead(alert.id)
        navEvents.post(
            PendingAction(if (play) AppLinks.ACTION_PLAY_BRIEF else AppLinks.ACTION_OPEN_BRIEF, alert.briefId),
        )
    }
}
