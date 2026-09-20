package com.naveen.civilscompanion.ui.voice

import com.naveen.civilscompanion.data.model.DailyPlan
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One block of today's plan, read from the DailyPlan row. */
data class PlanStep(val id: String, val title: String, val start: String, val minutes: Int)

/** Pure helpers for voice commands (no Android classes, so they can be tested on a computer). */
object VoiceLogic {
    private val SPEEDS = listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)

    /** The next speed step above (faster) or below the current one. */
    fun nextSpeed(current: Float, faster: Boolean): Float =
        if (faster) SPEEDS.firstOrNull { it > current + 0.01f } ?: SPEEDS.last()
        else SPEEDS.lastOrNull { it < current - 0.01f } ?: SPEEDS.first()

    fun formatSpeed(speed: Float): String = "${speed}x"

    /** First block of the plan that is neither done nor skipped, in start-time order. */
    fun nextStep(plan: DailyPlan): PlanStep? {
        val steps = plan.blocks.mapNotNull { b ->
            val id = (b["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            PlanStep(
                id = id,
                title = (b["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { "your next task" },
                start = (b["start"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                minutes = (b["minutes"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }.sortedBy { it.start }
        return steps.firstOrNull { step ->
            val state = (plan.completion[step.id] as? JsonPrimitive)?.contentOrNull
            state != "done" && state != "skipped"
        }
    }
}
