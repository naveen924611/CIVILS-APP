package com.naveen.civilscompanion.ui.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.repo.KvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer

/** Settings keys shared with the server: `test.negative_marking` (default false), `test.weekly_day` ("mon".."sun", default "sun"). */
object TestKeys {
    const val NEGATIVE = "test.negative_marking"
    const val WEEKLY_DAY = "test.weekly_day"
}

@HiltViewModel
class TestSettingsViewModel @Inject constructor(private val kv: KvRepository) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)

    val negative: StateFlow<Boolean> = kv.observe(TestKeys.NEGATIVE, Boolean.serializer(), false).stateIn(viewModelScope, started, false)
    val day: StateFlow<String> = kv.observe(TestKeys.WEEKLY_DAY, String.serializer(), "sun").stateIn(viewModelScope, started, "sun")

    fun setNegative(value: Boolean) {
        viewModelScope.launch { kv.put(TestKeys.NEGATIVE, Boolean.serializer(), value) }
    }

    fun setDay(value: String) {
        viewModelScope.launch { kv.put(TestKeys.WEEKLY_DAY, String.serializer(), value) }
    }
}
