package com.naveen.civilscompanion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.alarms.BriefAlarmScheduler
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.remote.dto.BriefSettingsDto
import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import com.naveen.civilscompanion.data.repo.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class SettingsUi(
    val slots: List<BriefSlotDto>,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val wifiOnly: Boolean = false,
    val exactAlarms: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sync: SyncRepository,
    private val prefs: Prefs,
    private val alarms: BriefAlarmScheduler,
) : ViewModel() {

    private var saved: List<BriefSlotDto> = prefs.briefSettings.briefs
    private val _state = MutableStateFlow(
        SettingsUi(saved, wifiOnly = prefs.wifiOnlyDownloads, exactAlarms = alarms.canScheduleExact()),
    )
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { sync.refreshBriefSettings() }.onSuccess { fresh ->
                if (!_state.value.dirty) {
                    saved = fresh.briefs
                    _state.value = _state.value.copy(slots = fresh.briefs)
                }
            }
        }
    }

    fun setEnabled(id: String, on: Boolean) = edit(id) { it.copy(enabled = on) }

    fun shiftTime(id: String, minutes: Int) = edit(id) { it.copy(time = BriefTimes.shift(it.time, minutes)) }

    fun toggleDay(id: String, day: Int) = edit(id) { slot ->
        val days = if (day in slot.days) slot.days - day else slot.days + day
        if (days.isEmpty()) slot else slot.copy(days = days.sorted())
    }

    fun addExtra() {
        val current = _state.value.slots
        val id = listOf("extra1", "extra2").firstOrNull { wanted -> current.none { it.id == wanted } } ?: return
        update(current + BriefSlotDto(id, "13:00"))
    }

    fun removeExtra(id: String) {
        if (id == "morning" || id == "evening") return
        update(_state.value.slots.filterNot { it.id == id })
    }

    fun setWifiOnly(on: Boolean) {
        prefs.wifiOnlyDownloads = on
        _state.value = _state.value.copy(wifiOnly = on)
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun save() {
        val slots = _state.value.slots
        _state.value = _state.value.copy(saving = true, message = null)
        viewModelScope.launch {
            val result = try {
                val fresh = sync.saveBriefSettings(BriefSettingsDto(slots))
                alarms.rescheduleAll()
                saved = fresh.briefs
                _state.value.copy(slots = fresh.briefs, dirty = false, saving = false, message = "Saved. New brief times are active.")
            } catch (e: HttpException) {
                _state.value.copy(saving = false, message = "The server did not accept these times (${e.code()}).")
            } catch (e: IOException) {
                _state.value.copy(saving = false, message = "Cannot reach the server. Nothing was changed.")
            }
            _state.value = result
        }
    }

    private fun edit(id: String, change: (BriefSlotDto) -> BriefSlotDto) =
        update(_state.value.slots.map { if (it.id == id) change(it) else it })

    private fun update(slots: List<BriefSlotDto>) {
        _state.value = _state.value.copy(slots = slots, dirty = slots != saved, message = null)
    }
}
