package com.naveen.civilscompanion.ui.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.data.model.Note
import com.naveen.civilscompanion.data.model.Sheet
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One revision sheet with the name and subject of its topic. */
data class SheetRow(val sheet: Sheet, val title: String, val subject: String)

data class SheetsUi(
    val rows: List<SheetRow> = emptyList(),
    /** Topics that have notes and have been started but have no sheet yet. */
    val missing: List<Topic> = emptyList(),
    /** Days left when an exam is 30 days away or less (last-month mode), else null. */
    val lastMonthDays: Int? = null,
    val todays: List<SheetRow> = emptyList(),
)

@HiltViewModel
class SheetsViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
) : ViewModel() {
    private val started = SharingStarted.WhileSubscribed(5_000)

    val state: StateFlow<SheetsUi> = combine(
        store.observe(Tables.Sheets, RecordQuery(limit = 2000)),
        store.observe(Tables.Topics, RecordQuery(limit = 5000)),
        store.observe(Tables.Notes, RecordQuery(limit = 5000)),
        store.observe(Tables.Exams, RecordQuery(limit = 50)),
    ) { sheets, topics, notes, exams -> build(sheets, topics, notes, exams) }
        .stateIn(viewModelScope, started, SheetsUi())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    private fun build(sheets: List<Sheet>, topics: List<Topic>, notes: List<Note>, exams: List<Exam>): SheetsUi {
        val byId = topics.associateBy { it.id }
        val rows = sheets.filter { it.status == "ready" && byId.containsKey(it.topicId) }
            .map { s -> SheetRow(s, byId.getValue(s.topicId).title, com.naveen.civilscompanion.ui.tests.TestLogic.subjectOf(s.topicId, byId)) }
            .sortedWith(compareBy<SheetRow>({ it.subject }, { it.title }))
        val haveSheet = rows.map { it.sheet.topicId }.toSet()
        val haveNotes = notes.filter { it.contentMd.isNotBlank() || it.sections.isNotEmpty() }.map { it.topicId }.toSet()
        val missing = topics.filter { it.id in haveNotes && it.id !in haveSheet && it.status != "not_started" && it.level >= 1 }
            .sortedBy { it.title.lowercase() }
        val today = TimeUtil.today()
        val last = SheetLogic.lastMonthDays(exams.map { SheetLogic.daysLeft(it.date, today) })
        val todays = if (last == null) emptyList() else {
            val picks = SheetLogic.pickSheets(rows.map { it.sheet.topicId to (byId[it.sheet.topicId]?.importance ?: 0.0) }, LocalDate.parse(today).toEpochDay())
            picks.mapNotNull { id -> rows.firstOrNull { it.sheet.topicId == id } }
        }
        return SheetsUi(rows = rows, missing = missing, lastMonthDays = last, todays = todays)
    }

    fun make(topicId: String) {
        viewModelScope.launch {
            jobs.enqueue("revision_sheet", jobPayload("topic_id" to topicId))
            _message.value = "The sheet will be made when you are online. It appears here by itself."
        }
    }

    fun makeAll(topics: List<Topic>) {
        viewModelScope.launch {
            topics.take(10).forEach { jobs.enqueue("revision_sheet", jobPayload("topic_id" to it.id)) }
            _message.value = "Making ${minOf(10, topics.size)} sheets. Press the button again later for the rest."
        }
    }
}
