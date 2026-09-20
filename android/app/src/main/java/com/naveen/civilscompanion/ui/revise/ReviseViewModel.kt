package com.naveen.civilscompanion.ui.revise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.RevisionRule
import com.naveen.civilscompanion.srs.QueueGroup
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Revise hub (queue, order, snooze) and the rules screen share this view model. */
@HiltViewModel
class ReviseViewModel @Inject constructor(private val repo: ReviseRepository) : ViewModel() {

    val queue: StateFlow<QueueState> =
        repo.observeQueue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueState())

    val settings: StateFlow<RevisionSettings> =
        repo.observeSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RevisionSettings())

    val rules: StateFlow<List<RevisionRule>> =
        repo.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Moves a group one place up (-1) or down (+1). This switches today to "My order". */
    fun move(key: String, delta: Int) {
        val keys = queue.value.groups.map { it.key }
        val next = ReviseData.move(keys, key, delta)
        if (next == keys) return
        viewModelScope.launch { repo.saveOrder(next) }
    }

    /** Back to "Smart order". */
    fun smartOrder() {
        viewModelScope.launch { repo.clearOrder() }
    }

    /** Snooze: the group's cards come back over the next 2 to 3 days. */
    fun snooze(group: QueueGroup) {
        val max = queue.value.config.maxCards
        viewModelScope.launch { repo.snooze(group.cardIds, max) }
    }

    // ------------------------------------------------------------------ rules
    fun setMaxCards(n: Int) {
        viewModelScope.launch { repo.saveMaxCards(n) }
    }

    fun setSundayReview(on: Boolean) {
        viewModelScope.launch { repo.saveSundayReview(on) }
    }

    /** time like "18:00"; ignored when it is not a valid 24-hour time. */
    fun setSlot(time: String) {
        val text = time.trim()
        if (!ReviseData.isValidTime(text)) return
        val parts = text.split(":")
        val padded = parts[0].padStart(2, '0') + ":" + parts[1]
        viewModelScope.launch { repo.saveSlot(padded) }
    }

    /** "Subject always first". Blank switches the rule off. */
    fun setPinnedSubject(subject: String) {
        val name = subject.trim()
        viewModelScope.launch {
            repo.saveRule("pinned_subject", JsonObject(mapOf("subject" to JsonPrimitive(name))), name.isNotEmpty())
        }
    }

    /** Daily group (current affairs): a few cards of it every day, however busy the queue is. */
    fun setDailyGroup(enabled: Boolean, cards: Int) {
        viewModelScope.launch {
            repo.saveRule(
                "daily_group",
                JsonObject(mapOf("group" to JsonPrimitive("Current affairs"), "cards" to JsonPrimitive(cards.coerceIn(1, 100)))),
                enabled,
            )
        }
    }
}
