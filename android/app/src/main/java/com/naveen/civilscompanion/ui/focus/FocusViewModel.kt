package com.naveen.civilscompanion.ui.focus

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.FocusSession
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.Order
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.ui.today.PlanBlock
import com.naveen.civilscompanion.ui.today.PlanBlocks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

data class FocusUi(
    val sessions: List<FocusSession> = emptyList(),
    val todayMinutes: Int = 0,
    /** Blocks of today's plan that are not done or skipped yet, offered as "what are you working on". */
    val tasks: List<PlanBlock> = emptyList(),
    val topicHits: List<Topic> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FocusViewModel @Inject constructor(
    private val timer: FocusTimer,
    private val dnd: FocusDnd,
    store: RecordStore,
) : ViewModel() {
    val timerState: StateFlow<FocusState> = timer.state
    val dndOn: StateFlow<Boolean> = timer.dndOn

    private val topicQuery = MutableStateFlow("")

    private val dayStart = TimeUtil.startOfDay(TimeUtil.today()).toDouble()

    val ui: StateFlow<FocusUi> = combine(
        store.observe(Tables.FocusSessions, RecordQuery(numberMin = dayStart, order = Order.NewestFirst)),
        store.observe(Tables.DailyPlans, RecordQuery(k1 = TimeUtil.today())),
        topicQuery.flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList<Topic>()) else store.observe(Tables.Topics, RecordQuery(contains = q, limit = 6))
        },
    ) { sessions, plans, topics ->
        val plan = plans.firstOrNull()
        val open = plan?.let { p ->
            PlanBlocks.parseAll(p.blocks).filter { b ->
                val status = (p.completion[b.id] as? JsonPrimitive)?.content
                status != PlanBlocks.DONE && status != PlanBlocks.SKIPPED
            }
        }.orEmpty()
        FocusUi(sessions, sessions.sumOf { it.minutes }, open, topics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUi())

    fun searchTopics(text: String) {
        topicQuery.value = text.trim()
    }

    fun hasDndAccess(): Boolean = dnd.hasAccess()

    fun dndSettingsIntent(): Intent = dnd.accessSettingsIntent()

    fun resync() = timer.resync()

    fun tick(now: Long) = timer.onPhaseEnded(now)

    fun start(style: String, focus: Int, rest: Int, task: String, topicId: String?, blockId: String?) =
        timer.start(style, focus, rest, task, topicId, blockId)

    fun pause() = timer.pause()

    fun resume() = timer.resume()

    fun finishEarly() = timer.finishEarly()

    fun skipBreak() = timer.skipBreak()

    fun cancel() = timer.cancelSession()

    fun answer(percent: Int) {
        viewModelScope.launch { timer.answer(percent) }
    }
}
