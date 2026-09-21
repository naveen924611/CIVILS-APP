package com.naveen.civilscompanion.widget

import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.today.PlanBlock
import com.naveen.civilscompanion.ui.today.PlanBlocks

/** What the home-screen widget shows. Plain data so the choosing rules can be tested. */
data class WidgetModel(
    val hasPlan: Boolean,
    val nextTime: String,
    val nextTitle: String,
    val nextRoute: String,
    val tasksLeft: Int,
    val tasksTotal: Int,
    val cardsDue: Int,
    val studiedText: String,
    val nextBrief: String,
)

object WidgetLogic {
    /**
     * The next task is the first block (by start time) not yet done or skipped. [completion] is DailyPlan.completion_json
     * as block id to "done" or "skipped".
     */
    fun build(
        blocks: List<PlanBlock>,
        completion: Map<String, String>,
        cardsDue: Int,
        studiedMinutes: Int,
        nextBrief: String,
    ): WidgetModel {
        val open = blocks.filter { completion[it.id] != PlanBlocks.DONE && completion[it.id] != PlanBlocks.SKIPPED }
        val next = open.firstOrNull()
        return WidgetModel(
            hasPlan = blocks.isNotEmpty(),
            nextTime = next?.let { PlanBlocks.displayTime(it.start) }.orEmpty(),
            nextTitle = next?.title.orEmpty(),
            nextRoute = next?.ref?.takeIf { it.isNotBlank() } ?: Routes.TODAY,
            tasksLeft = open.size,
            tasksTotal = blocks.size,
            cardsDue = cardsDue.coerceAtLeast(0),
            studiedText = studiedText(studiedMinutes),
            nextBrief = nextBrief,
        )
    }

    fun studiedText(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    /** One line about the plan, for the widget header. */
    fun planLine(model: WidgetModel): String = when {
        !model.hasPlan -> "No plan yet. Open the app to sync."
        model.tasksLeft == 0 -> "All ${model.tasksTotal} tasks done today"
        else -> "${model.tasksLeft} of ${model.tasksTotal} tasks left"
    }
}
