package com.naveen.civilscompanion.ui.report

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.data.model.WeeklyReport
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.speech.TtsSpeaker
import com.naveen.civilscompanion.ui.sheets.PdfDownloader
import com.naveen.civilscompanion.ui.sheets.SheetLogic
import com.naveen.civilscompanion.ui.today.PlannerApi
import com.naveen.civilscompanion.ui.today.RegenerateBody
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import retrofit2.HttpException

data class ReportUi(
    /** Newest week first. */
    val reports: List<WeeklyReport> = emptyList(),
    val selectedId: String? = null,
    val data: ReportData = ReportData(),
    val accepted: Boolean = false,
    /** Days to the nearest exam when it is 30 days away or less (last-month mode). */
    val lastMonthDays: Int? = null,
    val lastMonthExam: String = "",
    val listening: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val selected: WeeklyReport? get() = reports.firstOrNull { it.id == selectedId } ?: reports.firstOrNull()
}

/** The weekly report: read it, listen to it, save it as PDF, accept next week's plan. Also shows last-month mode. */
@HiltViewModel
class ReportViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val kv: KvRepository,
    private val tts: TtsSpeaker,
    private val pdf: PdfDownloader,
    private val planner: PlannerApi,
) : ViewModel() {
    private val choice = MutableStateFlow<String?>(null)
    private val extra = MutableStateFlow(ReportUi())

    private val base: StateFlow<ReportUi> = combine(
        store.observe(Tables.WeeklyReports, RecordQuery(limit = 60)),
        store.observe(Tables.Exams, RecordQuery(limit = 50)),
        kv.observe("plan.adjustments"),
        choice,
    ) { reports, exams, adjustments, picked -> build(reports, exams, adjustments, picked) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportUi())

    /** The screen state: what is stored plus the little bits that change while the screen is open. */
    val state: StateFlow<ReportUi> = combine(base, extra) { b, e -> b.copy(listening = e.listening, busy = e.busy, message = e.message) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportUi())

    private val _pdf = MutableSharedFlow<Uri>(extraBufferCapacity = 1)

    /** The downloaded PDF, ready for the share sheet. */
    val pdfReady: SharedFlow<Uri> = _pdf.asSharedFlow()

    private fun build(
        reports: List<WeeklyReport>,
        exams: List<Exam>,
        adjustments: kotlinx.serialization.json.JsonElement?,
        picked: String?,
    ): ReportUi {
        val sorted = reports.sortedByDescending { it.weekStart }
        val current = sorted.firstOrNull { it.id == picked } ?: sorted.firstOrNull()
        val data = current?.let { ReportLogic.parse(it.data) } ?: ReportData()
        val today = TimeUtil.today()
        val last = SheetLogic.lastMonthDays(exams.map { SheetLogic.daysLeft(it.date, today) })
        val nearest = exams.filter { SheetLogic.daysLeft(it.date, today) == last && last != null }.firstOrNull()
        return ReportUi(
            reports = sorted, selectedId = current?.id, data = data,
            accepted = data.nextWeek.weekStart.isNotBlank() && ReportLogic.isAccepted(adjustments, data.nextWeek.weekStart),
            lastMonthDays = last, lastMonthExam = nearest?.let { (it.name + " " + it.stage).trim() } ?: "",
        )
    }

    fun select(id: String) {
        choice.value = id
        stopListening()
    }

    fun clearMessage() {
        extra.value = extra.value.copy(message = null)
    }

    /** Asks the server to make (or refresh) the report of the current week. */
    fun makeNow() {
        viewModelScope.launch {
            val monday = ReportLogic.mondayOf(TimeUtil.today())
            jobs.enqueue("weekly_report", jobPayload("week_start" to monday))
            extra.value = extra.value.copy(message = "The report will be made when you are online. It appears here by itself.")
        }
    }

    /** "Accept next week's plan": saves plan.adjustments, then asks the planner to plan again so the change shows at once. */
    fun acceptPlan() {
        val data = state.value.data
        if (data.nextWeek.weekStart.isBlank()) return
        viewModelScope.launch {
            val json = ReportLogic.adjustmentsJson(data.nextWeek.adjustments, data.nextWeek.weekStart)
            val sent = kv.put("plan.adjustments", json)
            var text = "Next week's plan is accepted. The planner will use it."
            if (!sent) {
                text = "Accepted. It is saved on the tablet and will be sent when you are online."
            } else {
                try {
                    planner.regenerate(RegenerateBody(days = 14))
                } catch (e: IOException) {
                    text = "Accepted. The planner will use it tonight."
                } catch (e: HttpException) {
                    text = "Accepted. The planner will use it tonight."
                }
            }
            extra.value = extra.value.copy(message = text)
        }
    }

    fun listen() {
        val s = state.value
        val script = s.data.script.ifBlank { s.data.narrative }
        val pieces = SheetLogic.sentences(script)
        if (pieces.isEmpty()) return
        val speed = kv.get("voice.speed", Float.serializer(), 1f)
        extra.value = extra.value.copy(listening = true)
        tts.speakSentences(pieces, 0, "en-IN", speed, onSentence = {}, onDone = { extra.value = extra.value.copy(listening = false) })
    }

    fun stopListening() {
        tts.stop()
        extra.value = extra.value.copy(listening = false)
    }

    fun preparePdf() {
        val report = state.value.selected ?: return
        if (state.value.busy) return
        extra.value = extra.value.copy(busy = true)
        viewModelScope.launch {
            val file = pdf.weekly(report.id)
            if (file == null) {
                extra.value = extra.value.copy(busy = false, message = "The PDF needs the internet. Connect and try again.")
            } else {
                extra.value = extra.value.copy(busy = false)
                _pdf.tryEmit(pdf.uriOf(file))
            }
        }
    }

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }
}
