package com.naveen.civilscompanion.ui.telugu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.model.TeluguItem
import com.naveen.civilscompanion.data.model.TeluguProgress
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.data.repo.jobPayload
import com.naveen.civilscompanion.speech.TtsSpeaker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** One item in today's set. */
data class TodayItem(val item: TeluguItem, val done: Boolean, val score: Double?)

/** A piece of writing the owner sent, with the tutor's feedback when it has arrived. */
data class WritingEntry(val progress: TeluguProgress, val item: TeluguItem, val job: Job?) {
    val feedback: TeluguFeedback? get() = job?.takeIf { it.status == "done" }?.let { TeluguContent.feedback(it.result) }
    /** waiting | done | failed */
    val state: String get() = when (job?.status) {
        "done" -> "done"
        "failed" -> "failed"
        else -> "waiting"
    }
}

data class TeluguUi(
    val loaded: Boolean = false,
    val minutes: Int = TeluguPlan.DEFAULT_MINUTES,
    val today: List<TodayItem> = emptyList(),
    val items: List<TeluguItem> = emptyList(),
    val stats: TeluguStats = TeluguStats(),
    val writing: List<WritingEntry> = emptyList(),
)

@HiltViewModel
class TeluguViewModel @Inject constructor(
    private val store: RecordStore,
    private val jobs: JobRepository,
    private val kv: KvRepository,
    val tts: TtsSpeaker,
) : ViewModel() {

    private val itemsFlow = store.observe(Tables.TeluguItems, RecordQuery(limit = 3000))
    private val progressFlow = store.observe(Tables.TeluguProgressRows, RecordQuery(limit = 8000))
    private val jobsFlow = jobs.observeRecent("telugu_feedback", 200)
    private val minutesFlow = kv.observe(TeluguPlan.KEY_MINUTES).map { TeluguPlan.parseMinutes(it) }

    val ui: StateFlow<TeluguUi> = combine(itemsFlow, progressFlow, jobsFlow, minutesFlow) { items, progress, jobList, minutes ->
        build(items, progress, jobList, minutes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TeluguUi())

    init {
        // When the tutor's feedback arrives, keep its score on the progress row (the server also does this).
        viewModelScope.launch {
            combine(progressFlow, jobsFlow) { progress, jobList -> progress to jobList }.collect { (progress, jobList) ->
                for (job in jobList) {
                    if (job.status != "done") continue
                    val pid = (job.payload["progress_id"] as? JsonPrimitive)?.content ?: continue
                    val row = progress.firstOrNull { it.id == pid } ?: continue
                    if (row.score != null) continue
                    val score = (job.result?.get("score") as? JsonPrimitive)?.content?.toDoubleOrNull() ?: continue
                    store.update(Tables.TeluguProgressRows, pid) { it.copy(score = TeluguPlan.fractionOf10(score)) }
                }
            }
        }
    }

    private fun build(items: List<TeluguItem>, progress: List<TeluguProgress>, jobList: List<Job>, minutes: Int): TeluguUi {
        val today = TimeUtil.today()
        val history = progress.filter { it.done && !it.deleted }.map {
            PDone(it.itemId, it.score, TeluguPlan.dayOf(it.at, TimeUtil.india) ?: today, TeluguPlan.epochOf(it.at))
        }
        val live = items.filter { !it.deleted }
        val plain = live.map { PItem(it.id, it.kind, it.position) }
        val byId = live.associateBy { it.id }
        val latest = TeluguPlan.latestByItem(history)
        val doneToday = history.filter { it.day == today }.map { it.itemId }.toSet()
        val todayItems = TeluguPlan.pickToday(plain, history, today, minutes).mapNotNull { p ->
            byId[p.id]?.let { TodayItem(it, p.id in doneToday, latest[p.id]?.score) }
        }
        val jobByProgress = jobList.mapNotNull { j ->
            (j.payload["progress_id"] as? JsonPrimitive)?.content?.let { it to j }
        }.toMap()
        val writing = progress
            .filter { !it.deleted && it.answer.isNotBlank() }
            .sortedByDescending { TeluguPlan.epochOf(it.at) }
            .mapNotNull { row -> byId[row.itemId]?.let { WritingEntry(row, it, jobByProgress[row.id]) } }
        return TeluguUi(true, minutes, todayItems, live, TeluguPlan.stats(plain, history, today), writing)
    }

    /** Saves one finished practice (a word, a passage). score 0 to 1. */
    fun record(itemId: String, score: Double) {
        viewModelScope.launch {
            store.save(
                Tables.TeluguProgressRows,
                TeluguProgress(id = TimeUtil.newId(), itemId = itemId, done = true, score = score, at = TimeUtil.nowIso()),
            )
        }
    }

    /** Saves the answer and asks the tutor to check it (works offline: the job waits for the internet). */
    fun sendForFeedback(item: TeluguItem, answer: String) {
        val text = answer.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val pid = TimeUtil.newId()
            store.save(
                Tables.TeluguProgressRows,
                TeluguProgress(id = pid, itemId = item.id, done = true, score = null, answer = text, at = TimeUtil.nowIso()),
            )
            jobs.enqueue("telugu_feedback", jobPayload("item_id" to item.id, "answer" to text, "progress_id" to pid))
        }
    }

    /** Reads Telugu text aloud. If the tablet has no Telugu voice it may read nothing useful (see Settings of the tablet). */
    fun speak(text: String) {
        tts.speak(text, "te-IN", 0.9f)
    }

    fun stopSpeaking() {
        tts.stop()
    }

    override fun onCleared() {
        tts.stop()
        super.onCleared()
    }
}
