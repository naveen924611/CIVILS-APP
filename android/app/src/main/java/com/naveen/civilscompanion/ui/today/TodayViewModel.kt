package com.naveen.civilscompanion.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.model.DailyPlan
import com.naveen.civilscompanion.data.model.Exam
import com.naveen.civilscompanion.data.model.LibDocument
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.JobRepository
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.ui.revise.ReviseData
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

data class TodayBlockUi(val block: PlanBlock, val status: String) {
    val done: Boolean get() = status == PlanBlocks.DONE
}

data class ExamCountdown(val label: String, val text: String)

data class ContinueItem(val docId: String, val title: String, val detail: String)

data class WeekSummary(val blocksDone: Int = 0, val blocksPlanned: Int = 0, val cardsRevised: Int = 0, val hoursText: String = "")

data class TodayUi(
    val loaded: Boolean = false,
    val date: String = "",
    val longDate: String = "",
    val greeting: String = "Hello",
    val hoursText: String = "",
    val summary: String = "",
    val blocks: List<TodayBlockUi> = emptyList(),
    val planId: String? = null,
    val offline: Boolean = false,
    val waiting: Int = 0,
    val exams: List<ExamCountdown> = emptyList(),
    val cont: ContinueItem? = null,
    val week: WeekSummary = WeekSummary(),
    val message: String? = null,
    val busy: Boolean = false,
)

private data class Stage1(
    val plans: List<DailyPlan>,
    val ticks: List<PlanTick>,
    val hoursJson: kotlinx.serialization.json.JsonElement?,
    val teluguJson: kotlinx.serialization.json.JsonElement?,
    val slot: String,
)

private data class Stage2(val exams: List<Exam>, val docs: List<LibDocument>, val waiting: Int, val online: Boolean, val dueCards: Int)

private data class Stage3(val allPlans: List<DailyPlan>, val weekReviews: Int)

/** Today (spec 6.1): the day's plan with Start buttons, offline chip, waiting questions, countdowns and the week so far. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val store: RecordStore,
    private val kv: KvRepository,
    private val jobs: JobRepository,
    private val prefs: Prefs,
    private val plans: PlanRepository,
    connectivity: ConnectivityWatcher,
) : ViewModel() {

    private val flash = MutableStateFlow<Pair<String?, Boolean>>(null to false)
    private val online = connectivity.observe()

    private val dates = flow {
        while (true) {
            emit(TimeUtil.today())
            delay(60_000)
        }
    }.distinctUntilChanged()

    private val built = dates.flatMapLatest { date -> buildFlow(date) }

    val ui: StateFlow<TodayUi> = combine(built, flash) { base, f -> base.copy(message = f.first, busy = f.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    init {
        // First day (or a new day before the server has planned it): ask once, quietly. Offline is fine: the tablet's own plan shows.
        viewModelScope.launch {
            delay(1_500)
            if (!plans.hasServerPlan(TimeUtil.today())) {
                plans.replan(7)
            }
        }
    }

    private fun buildFlow(date: String) = combine(
        stage1(date),
        stage2(date),
        stage3(date),
    ) { a, b, c -> build(date, a, b, c) }

    private fun stage1(date: String) = combine(
        plans.observeDay(date),
        plans.observeTicks(date),
        kv.observe(StudyPrefs.KEY_HOURS),
        kv.observe(StudyPrefs.KEY_TELUGU),
        kv.observe(ReviseKeys.SLOT),
    ) { p, t, h, tel, slot ->
        Stage1(p, t, h, tel, (slot as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: "18:00")
    }

    private fun stage2(date: String) = combine(
        store.observe(Tables.Exams),
        store.observe(Tables.Documents, RecordQuery(order = Order.NewestFirst, limit = 12)),
        jobs.observeWaiting(),
        online,
        store.observeCount(Tables.Cards, RecordQuery(numberMax = ReviseData.endOfDay(LocalDate.parse(date)).toEpochMilli().toDouble())),
    ) { exams, docs, waiting, isOnline, due -> Stage2(exams, docs, waiting, isOnline, due) }

    private fun stage3(date: String) = combine(
        plans.observeAll(),
        store.observeCount(Tables.Reviews, RecordQuery(numberMin = ReviseData.startOfDay(weekStart(LocalDate.parse(date))).toEpochMilli().toDouble())),
    ) { all, reviews -> Stage3(all, reviews) }

    private fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    private fun build(date: String, a: Stage1, b: Stage2, c: Stage3): TodayUi {
        val day = LocalDate.parse(date)
        val hours = StudyPrefs.parseHours(a.hoursJson)
        val serverPlan = a.plans.firstOrNull { it.blocks.isNotEmpty() }
        val blocks: List<PlanBlock>
        val statuses: Map<String, String>
        if (serverPlan != null) {
            blocks = PlanBlocks.parseAll(serverPlan.blocks)
            statuses = serverPlan.completion.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }
        } else {
            val slots = prefs.briefSettings.briefs
            val weekday = day.dayOfWeek.value - 1
            fun briefTime(id: String): String? = slots.firstOrNull { it.id == id && it.enabled && weekday in it.days }?.time
            blocks = PlanBlocks.fallback(
                date = date,
                hours = StudyPrefs.hoursFor(day, hours),
                morning = briefTime("morning"),
                evening = briefTime("evening"),
                revisionSlot = a.slot,
                dueCards = Math.min(b.dueCards, 80),
                teluguMinutes = StudyPrefs.parseTelugu(a.teluguJson),
            )
            statuses = a.ticks.associate { it.blockId to it.status }
        }
        val minutes = PlanBlocks.totalMinutes(blocks)
        val now = LocalTime.now(ReviseData.INDIA)
        val weekDates = (0..6).map { weekStart(day).plusDays(it.toLong()).toString() }.toSet()
        val weekPlans = c.allPlans.filter { it.date in weekDates }
        val planned = weekPlans.sumOf { it.blocks.size }
        val done = weekPlans.sumOf { p -> p.completion.values.count { (it as? JsonPrimitive)?.content == PlanBlocks.DONE } }
        return TodayUi(
            loaded = true,
            date = date,
            longDate = PlanBlocks.longDate(day),
            greeting = PlanBlocks.greeting(now.hour),
            hoursText = if (minutes == 0) "Rest day" else PlanBlocks.hoursText(minutes) + " planned",
            summary = serverPlan?.summary.orEmpty(),
            blocks = blocks.map { TodayBlockUi(it, PlanBlocks.statusOf(statuses, it.id)) },
            planId = serverPlan?.id,
            offline = !b.online,
            waiting = b.waiting,
            exams = examCountdowns(b.exams, day),
            cont = continueItem(b.docs),
            week = WeekSummary(done, planned, c.weekReviews, PlanBlocks.hoursText(Math.round(StudyPrefs.weekHours(hours) * 60).toInt())),
        )
    }

    private fun examCountdowns(exams: List<Exam>, today: LocalDate): List<ExamCountdown> {
        val dated = exams.filter { ExamDates.daysLeft(it.date, today).let { d -> d != null && d >= 0 } }
            .sortedBy { ExamDates.daysLeft(it.date, today) ?: Int.MAX_VALUE }
        val undated = exams.filter { ExamDates.parseDay(it.date) == null }
        return (dated + undated).take(4).map { ExamCountdown("${it.name} ${it.stage}", ExamDates.countdownText(it.date, today)) }
    }

    private fun continueItem(docs: List<LibDocument>): ContinueItem? {
        val doc = docs.firstOrNull { it.readingPosition.isNotEmpty() && !it.deleted } ?: return null
        val page = (doc.readingPosition["page"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull }
        return ContinueItem(doc.id, doc.title, if (page != null) "Page $page" else "Where you stopped")
    }

    // ------------------------------------------------------------------ actions
    fun setDone(block: TodayBlockUi, done: Boolean) {
        val s = ui.value
        viewModelScope.launch { plans.setDone(s.planId, s.date, block.block.id, done) }
    }

    fun replan() {
        viewModelScope.launch {
            flash.value = null to true
            val message = plans.replan(7)
            flash.value = (message ?: "Your plan is up to date.") to false
        }
    }

    fun dismissMessage() {
        flash.value = null to false
    }

    private object ReviseKeys {
        const val SLOT = "revision.slot_time"
    }
}
