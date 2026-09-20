package com.naveen.civilscompanion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val prefs: Prefs,
    private val navEvents: NavEvents,
) : ViewModel() {
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
