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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class ExamsUi(
    val exams: List<Exam> = emptyList(),
    val hours: Map<String, Double> = StudyPrefs.DEFAULT_HOURS,
    val telugu: Int = StudyPrefs.DEFAULT_TELUGU,
    /** 0 all equal, 1 more on UPSC, 2 more on APPSC, 3 more on SI (Civil). */
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

    /**
     * The four exams the owner started with (fresh install), plus the SLPRB SI (Civil) rows, added ONCE for everybody:
     * the flag `goal.si_seeded` stops a deleted SI row from coming back. The lock makes two calls at the same time safe.
     */
    fun seedDefaults() {
        viewModelScope.launch {
            seedLock.withLock {
                val existing = store.list(Tables.Exams)
                if (existing.isEmpty()) {
                    val defaults = listOf("UPSC CSE" to "Prelims", "UPSC CSE" to "Mains", "APPSC Group-I" to "Prelims", "APPSC Group-I" to "Mains")
                    defaults.forEach { (name, stage) ->
                        store.save(Tables.Exams, Exam(id = TimeUtil.newId(), name = name, stage = stage, date = null, isTentative = true))
                    }
                }
                addSiRowsOnce(existing)
            }
        }
    }

    /** For an existing install: adds the three SI rows once (never on an empty list, that is seedDefaults' job). */
    fun seedSiOnce() {
        viewModelScope.launch {
            seedLock.withLock {
                val existing = store.list(Tables.Exams)
                if (existing.isNotEmpty()) addSiRowsOnce(existing)
            }
        }
    }

    /** Must run inside seedLock. Checks the flag first and sets it right after, so a deleted SI row is not added again. */
    private suspend fun addSiRowsOnce(existing: List<Exam>) {
        val seeded = (kv.get(KEY_SI_SEEDED) as? JsonPrimitive)?.booleanOrNull == true
        if (seeded) return
        if (existing.none { it.name == SI_EXAM_NAME }) {
            SI_STAGES.forEach { stage ->
                store.save(Tables.Exams, Exam(id = TimeUtil.newId(), name = SI_EXAM_NAME, stage = stage, date = null, isTentative = true))
            }
        }
        kv.put(KEY_SI_SEEDED, JsonPrimitive(true))
    }

    private companion object {
        const val KEY_SI_SEEDED = "goal.si_seeded"
        const val SI_EXAM_NAME = "SLPRB SI (Civil)"
        val SI_STAGES = listOf("Prelims", "Physical (PMT and PET)", "Final Written")
        val seedLock = Mutex()
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
