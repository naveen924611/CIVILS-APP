package com.naveen.civilscompanion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    val loggedIn: StateFlow<Boolean> = auth.loggedIn
    fun logout() = viewModelScope.launch { auth.logout() }
}
