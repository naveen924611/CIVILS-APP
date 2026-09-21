package com.naveen.civilscompanion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.alarms.BriefAlarmScheduler
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.remote.dto.BriefSettingsDto
import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.data.repo.SyncRepository
import com.naveen.civilscompanion.notify.DayNotifier
import com.naveen.civilscompanion.notify.DayNotifyLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import retrofit2.HttpException

data class SettingsUi(
    val slots: List<BriefSlotDto>,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val wifiOnly: Boolean = false,
    val exactAlarms: Boolean = true,
    val theme: String = "system",
    val textScale: Float = 1f,
    val notifyRevision: Boolean = true,
    val notifyWeekly: Boolean = true,
    val summary: DayNotifyLogic.DaySummary = DayNotifyLogic.DaySummary(),
    val quiet: DayNotifyLogic.Quiet = DayNotifyLogic.Quiet(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sync: SyncRepository,
    private val prefs: Prefs,
    private val alarms: BriefAlarmScheduler,
    private val kv: KvRepository,
    private val dayNotifier: DayNotifier,
) : ViewModel() {

    private var saved: List<BriefSlotDto> = prefs.briefSettings.briefs
    private val _state = MutableStateFlow(
        SettingsUi(saved, wifiOnly = prefs.wifiOnlyDownloads, exactAlarms = alarms.canScheduleExact()),
    )
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

    init {
        watch(UI_THEME) { e -> copy(theme = (e as? JsonPrimitive)?.content?.takeIf { it in THEMES } ?: "system") }
        watch(UI_SCALE) { e -> copy(textScale = ((e as? JsonPrimitive)?.floatOrNull ?: 1f).coerceIn(0.8f, 1.6f)) }
        watch(DayNotifyLogic.KEY_REVISION) { e -> copy(notifyRevision = DayNotifyLogic.parseBool(e, true)) }
        watch(DayNotifyLogic.KEY_WEEKLY) { e -> copy(notifyWeekly = DayNotifyLogic.parseBool(e, true)) }
        watch(DayNotifyLogic.KEY_SUMMARY) { e -> copy(summary = DayNotifyLogic.parseSummary(e)) }
        watch(DayNotifyLogic.KEY_QUIET) { e -> copy(quiet = DayNotifyLogic.parseQuiet(e)) }
        runCatching { dayNotifier.rescheduleAll() } // makes sure the day alarms exist (first launch after an update)
        viewModelScope.launch {
            runCatching { sync.refreshBriefSettings() }.onSuccess { fresh ->
                if (!_state.value.dirty) {
                    saved = fresh.briefs
                    _state.value = _state.value.copy(slots = fresh.briefs)
                }
            }
        }
    }

    private fun watch(key: String, change: SettingsUi.(kotlinx.serialization.json.JsonElement?) -> SettingsUi) {
        viewModelScope.launch {
            kv.observe(key).collect { e -> _state.update { it.change(e) } }
        }
    }

    private fun put(key: String, value: kotlinx.serialization.json.JsonElement, reschedule: Boolean = false) {
        viewModelScope.launch {
            kv.put(key, value)
            if (reschedule) runCatching { dayNotifier.rescheduleAll() }
        }
    }

    fun setTheme(theme: String) = put(UI_THEME, JsonPrimitive(theme))

    fun setTextScale(scale: Float) = put(UI_SCALE, JsonPrimitive(scale.coerceIn(0.8f, 1.6f)))

    fun setNotifyRevision(on: Boolean) = put(DayNotifyLogic.KEY_REVISION, JsonPrimitive(on), reschedule = true)

    fun setNotifyWeekly(on: Boolean) = put(DayNotifyLogic.KEY_WEEKLY, JsonPrimitive(on), reschedule = true)

    fun setSummary(next: DayNotifyLogic.DaySummary) = put(DayNotifyLogic.KEY_SUMMARY, DayNotifyLogic.summaryJson(next), reschedule = true)

    fun setQuiet(next: DayNotifyLogic.Quiet) = put(DayNotifyLogic.KEY_QUIET, DayNotifyLogic.quietJson(next))

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

    private companion object {
        const val UI_THEME = "ui.theme"
        const val UI_SCALE = "ui.text_scale"
        val THEMES = setOf("system", "light", "dark")
    }
}
