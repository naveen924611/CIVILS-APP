package com.naveen.civilscompanion.ui.ask

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Ask about this": any screen calls AskDraft.set("Explain Article 21 on this page", topicId) and then
 * navigates to Routes.ASK. The Ask screen shows the text in its input box (owner presses send) and clears it.
 * FOUNDATION FILE: only the Ask owner may change it.
 */
@Singleton
class AskDraft @Inject constructor() {
    data class Draft(val text: String, val topicId: String? = null, val sourceLabel: String? = null)

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()

    fun set(text: String, topicId: String? = null, sourceLabel: String? = null) {
        _draft.value = Draft(text, topicId, sourceLabel)
    }

    fun clear() {
        _draft.value = null
    }
}
