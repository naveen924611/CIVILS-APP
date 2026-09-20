package com.naveen.civilscompanion.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naveen.civilscompanion.data.model.DailyPlan
import com.naveen.civilscompanion.ui.revise.ReviseData
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

data class DayUi(
    val date: String,
    val label: String,
    val isToday: Boolean,
    val hasPlan: Boolean,
    val hoursText: String,
    val summary: String,
    val blocks: List<TodayBlockUi>,
)

data class PlannerUi(
    val loaded: Boolean = false,
    val days: List<DayUi> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false,
)

/** The week view of the planner (spec 7.6): the next seven days as the server planned them. */
@HiltViewModel
class PlannerViewModel @Inject constructor(private val plans: PlanRepository) : ViewModel() {

    private val flash = MutableStateFlow<Pair<String?, Boolean>>(null to false)

    val ui: StateFlow<PlannerUi> = combine(plans.observeAll(), flash) { all, f ->
        PlannerUi(loaded = true, days = buildDays(all), message = f.first, busy = f.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannerUi())

    private fun buildDays(all: List<DailyPlan>): List<DayUi> {
        val today = LocalDate.now(ReviseData.INDIA)
        return (0..6).map { i ->
            val day = today.plusDays(i.toLong())
            val key = day.toString()
            val plan = all.filter { it.date == key && it.blocks.isNotEmpty() }.maxByOrNull { it.updatedAt }
            val blocks = plan?.let { PlanBlocks.parseAll(it.blocks) }.orEmpty()
            val statuses = plan?.completion?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }.orEmpty()
            DayUi(
                date = key,
                label = PlanBlocks.shortDate(day),
                isToday = i == 0,
                hasPlan = plan != null,
                hoursText = if (plan == null) "" else PlanBlocks.hoursText(PlanBlocks.totalMinutes(blocks)),
                summary = plan?.summary.orEmpty(),
                blocks = blocks.map { TodayBlockUi(it, PlanBlocks.statusOf(statuses, it.id)) },
            )
        }
    }

    fun replan() {
        viewModelScope.launch {
            flash.value = null to true
            val message = plans.replan(7)
            flash.value = (message ?: "The week is planned again.") to false
        }
    }

    fun dismissMessage() {
        flash.value = null to false
    }
}
