package com.naveen.civilscompanion.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.ui.goals.GoalsData.PetEntry
import com.naveen.civilscompanion.ui.goals.GoalsData.PmtInput
import com.naveen.civilscompanion.ui.goals.GoalsData.SiProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Goals screen shows, read from the saved settings. */
data class GoalsUi(
    val applied: Map<String, Boolean> = GoalsData.parseApplied(null),
    val profile: SiProfile = SiProfile(),
    val checklist: Map<String, Boolean> = emptyMap(),
    val pmt: PmtInput = PmtInput(),
    val petLog: List<PetEntry> = emptyList(),
)

/**
 * The SI (Civil) goal: application ticks, the owner's profile, checklist, body measures and the PET log.
 * All of it lives in settings (KvRepository), so it works offline and syncs later. No passwords or payment details.
 */
@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val kv: KvRepository,
) : ViewModel() {

    private fun snapshot(): GoalsUi = GoalsUi(
        applied = GoalsData.parseApplied(kv.get(GoalsData.KEY_APPLIED)),
        profile = GoalsData.parseProfile(kv.get(GoalsData.KEY_PROFILE)),
        checklist = GoalsData.parseChecklist(kv.get(GoalsData.KEY_CHECKLIST)),
        pmt = GoalsData.parsePmt(kv.get(GoalsData.KEY_PMT)),
        petLog = GoalsData.parsePetLog(kv.get(GoalsData.KEY_PET_LOG)),
    )

    val ui: StateFlow<GoalsUi> = combine(
        kv.observe(GoalsData.KEY_APPLIED),
        kv.observe(GoalsData.KEY_PROFILE),
        kv.observe(GoalsData.KEY_CHECKLIST),
        kv.observe(GoalsData.KEY_PMT),
        kv.observe(GoalsData.KEY_PET_LOG),
    ) { applied, profile, checklist, pmt, petLog ->
        GoalsUi(
            applied = GoalsData.parseApplied(applied),
            profile = GoalsData.parseProfile(profile),
            checklist = GoalsData.parseChecklist(checklist),
            pmt = GoalsData.parsePmt(pmt),
            petLog = GoalsData.parsePetLog(petLog),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot())

    fun setApplied(key: String, value: Boolean) {
        val next = GoalsData.parseApplied(kv.get(GoalsData.KEY_APPLIED)) + (key to value)
        viewModelScope.launch { kv.put(GoalsData.KEY_APPLIED, GoalsData.appliedJson(next)) }
    }

    fun updateProfile(change: (SiProfile) -> SiProfile) {
        val next = change(GoalsData.parseProfile(kv.get(GoalsData.KEY_PROFILE)))
        viewModelScope.launch { kv.put(GoalsData.KEY_PROFILE, GoalsData.profileJson(next)) }
    }

    fun setChecked(key: String, value: Boolean) {
        val next = GoalsData.parseChecklist(kv.get(GoalsData.KEY_CHECKLIST)) + (key to value)
        viewModelScope.launch { kv.put(GoalsData.KEY_CHECKLIST, GoalsData.checklistJson(next)) }
    }

    fun updatePmt(change: (PmtInput) -> PmtInput) {
        val next = change(GoalsData.parsePmt(kv.get(GoalsData.KEY_PMT)))
        viewModelScope.launch { kv.put(GoalsData.KEY_PMT, GoalsData.pmtJson(next)) }
    }

    /** Adds one PET entry (already checked by GoalsData.buildEntry). */
    fun addPet(entry: PetEntry) {
        val next = GoalsData.addPetEntry(GoalsData.parsePetLog(kv.get(GoalsData.KEY_PET_LOG)), entry)
        viewModelScope.launch { kv.put(GoalsData.KEY_PET_LOG, GoalsData.petLogJson(next)) }
    }

    fun deletePet(id: String) {
        val next = GoalsData.removePetEntry(GoalsData.parsePetLog(kv.get(GoalsData.KEY_PET_LOG)), id)
        viewModelScope.launch { kv.put(GoalsData.KEY_PET_LOG, GoalsData.petLogJson(next)) }
    }

    fun newId(): String = TimeUtil.newId()
}
