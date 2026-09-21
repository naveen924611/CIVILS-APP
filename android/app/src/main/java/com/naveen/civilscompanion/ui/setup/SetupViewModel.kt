package com.naveen.civilscompanion.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.alarms.BriefAlarmScheduler
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.remote.dto.BriefSettingsDto
import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import com.naveen.civilscompanion.data.repo.SyncRepository
import com.naveen.civilscompanion.notify.DayNotifier
import com.naveen.civilscompanion.ui.today.PlannerApi
import com.naveen.civilscompanion.ui.today.RegenerateBody
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class SetupUi(
    val slots: List<BriefSlotDto> = emptyList(),
    val message: String? = null,
    val planning: Boolean = false,
)

/** Brief times and the "make my first plan" step of the setup wizard. */
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val prefs: Prefs,
    private val sync: SyncRepository,
    private val alarms: BriefAlarmScheduler,
    private val planner: PlannerApi,
    private val dayNotifier: DayNotifier,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUi(slots = prefs.briefSettings.briefs.filter { it.id == "morning" || it.id == "evening" }))
    val state: StateFlow<SetupUi> = _state.asStateFlow()

    fun shift(id: String, minutes: Int) {
        _state.update { ui -> ui.copy(slots = ui.slots.map { if (it.id == id) it.copy(time = BriefTimes.shift(it.time, minutes)) else it }) }
    }

    fun toggle(id: String, on: Boolean) {
        _state.update { ui -> ui.copy(slots = ui.slots.map { if (it.id == id) it.copy(enabled = on) else it }) }
    }

    /** Sends the times to the server and sets the alarms. Offline: kept on the tablet only. */
    fun saveTimes(then: () -> Unit) {
        val edited = _state.value.slots
        viewModelScope.launch {
            // keep any extra briefs the owner already had
            val all = prefs.briefSettings.briefs.filter { old -> edited.none { it.id == old.id } } + edited
            val text = try {
                val fresh = sync.saveBriefSettings(BriefSettingsDto(all))
                prefs.briefSettings = fresh
                null
            } catch (e: IOException) {
                prefs.briefSettings = BriefSettingsDto(all)
                "Saved on this tablet. Open Settings when you are online to send the times to the server."
            } catch (e: HttpException) {
                prefs.briefSettings = BriefSettingsDto(all)
                "The server did not accept the times (${e.code()}). They are kept on this tablet."
            }
            runCatching { alarms.rescheduleAll() }
            _state.update { it.copy(message = text) }
            then()
        }
    }

    /** Asks the server for the first days' plan (needs internet; skipped quietly when offline) and sets the day alarms. */
    fun finish(then: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(planning = true, message = null) }
            val note = try {
                planner.regenerate(RegenerateBody(7))
                null
            } catch (e: IOException) {
                "No internet just now. Your first plan appears after the next sync."
            } catch (e: HttpException) {
                "The plan will be made on the server soon (${e.code()})."
            }
            runCatching { dayNotifier.rescheduleAll() }
            _state.update { it.copy(planning = false, message = note) }
            then()
        }
    }
}
