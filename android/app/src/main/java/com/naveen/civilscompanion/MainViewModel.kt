package com.naveen.civilscompanion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.auth.AuthRepository
import com.naveen.civilscompanion.data.repo.KvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** theme: system | light | dark. textScale: 1.0 is normal size. */
data class UiPrefs(val theme: String = "system", val textScale: Float = 1f)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val prefs: Prefs,
    private val navEvents: NavEvents,
    kv: KvRepository,
) : ViewModel() {
    /** Look-and-feel choices from Settings (kept in the shared settings under ui.theme and ui.text_scale). */
    val ui: StateFlow<UiPrefs> = combine(kv.observe("ui.theme"), kv.observe("ui.text_scale")) { theme, scale ->
        UiPrefs(
            theme = (theme as? JsonPrimitive)?.content ?: "system",
            textScale = ((scale as? JsonPrimitive)?.floatOrNull ?: 1f).coerceIn(0.8f, 1.6f),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiPrefs())

    val loggedIn: StateFlow<Boolean> = auth.loggedIn
    val pending: StateFlow<PendingAction?> = navEvents.pending

    private val _setupDone = MutableStateFlow(prefs.setupDone)
    val setupDone: StateFlow<Boolean> = _setupDone.asStateFlow()

    fun logout() = viewModelScope.launch { auth.logout() }

    fun finishSetup() {
        prefs.setupDone = true
        _setupDone.value = true
    }

    fun reopenSetup() {
        _setupDone.value = false
    }

    fun consumePending() = navEvents.consume()
}
