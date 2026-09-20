package com.naveen.civilscompanion.ui.exams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.ui.today.ExamDates
import com.naveen.civilscompanion.ui.today.StudyPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

data class ExamsUi(
    val exams: List<Exam> = emptyList(),
    val hours: Map<String, Double> = StudyPrefs.DEFAULT_HOURS,
    val telugu: Int = StudyPrefs.DEFAULT_TELUGU,
    /** 0 both equal, 1 more on UPSC, 2 more on APPSC. */
    val priorityMode: Int = 0,
    val loaded: Boolean = false,
)

/** Exams (dates, tentative), exam priority, study hours per weekday and Telugu minutes. Shared by the Exams screen, setup and Settings. */
@HiltViewModel
class ExamsViewModel @Inject constructor(
    private val store: RecordStore,
    private val kv: KvRepository,
) : ViewModel() {

    val ui: StateFlow<ExamsUi> = combine(
        store.observe(Tables.Exams),
        kv.observe(StudyPrefs.KEY_HOURS),
        kv.observe(StudyPrefs.KEY_TELUGU),
        kv.observe(StudyPrefs.KEY_PRIORITY),
    ) { exams, hours, telugu, priority ->
        ExamsUi(
            exams = exams.sortedWith(compareBy<Exam>({ it.name }, { it.stage })),
            hours = StudyPrefs.parseHours(hours),
            telugu = StudyPrefs.parseTelugu(telugu),
            priorityMode = StudyPrefs.priorityMode(StudyPrefs.parsePriority(priority)),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExamsUi())

    /** First run: the four exams the owner is preparing for, dates not announced. Does nothing when exams already exist. */
    fun seedDefaults() {
        viewModelScope.launch {
            if (store.list(Tables.Exams).isNotEmpty()) return@launch
            val defaults = listOf("UPSC CSE" to "Prelims", "UPSC CSE" to "Mains", "APPSC Group-I" to "Prelims", "APPSC Group-I" to "Mains")
            defaults.forEach { (name, stage) ->
                store.save(Tables.Exams, Exam(id = TimeUtil.newId(), name = name, stage = stage, date = null, isTentative = true))
            }
        }
    }

    /** day null = "Date not announced". */
    fun setDate(id: String, day: LocalDate?) {
        viewModelScope.launch {
            store.update(Tables.Exams, id) { it.copy(date = day?.let { d -> ExamDates.toStored(d) }, isTentative = if (day == null) true else it.isTentative) }
        }
    }

    fun setTentative(id: String, tentative: Boolean) {
        viewModelScope.launch { store.update(Tables.Exams, id) { it.copy(isTentative = tentative) } }
    }

    fun addExam(name: String, stage: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            store.save(Tables.Exams, Exam(id = TimeUtil.newId(), name = trimmed, stage = stage, date = null, isTentative = true))
        }
    }

    fun deleteExam(id: String) {
        viewModelScope.launch { store.delete(Tables.Exams, id) }
    }

    fun setHours(day: String, hours: Double) {
        val next = StudyPrefs.parseHours(kv.get(StudyPrefs.KEY_HOURS)) + (day to hours)
        viewModelScope.launch { kv.put(StudyPrefs.KEY_HOURS, StudyPrefs.hoursJson(next)) }
    }

    fun setTelugu(minutes: Int) {
        viewModelScope.launch { kv.put(StudyPrefs.KEY_TELUGU, JsonPrimitive(minutes.coerceIn(0, 120))) }
    }

    fun setPriorityMode(mode: Int) {
        viewModelScope.launch { kv.put(StudyPrefs.KEY_PRIORITY, StudyPrefs.priorityJson(StudyPrefs.priorityFor(mode))) }
    }
}
