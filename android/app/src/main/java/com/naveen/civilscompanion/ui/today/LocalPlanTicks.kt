package com.naveen.civilscompanion.ui.today

import com.naveen.civilscompanion.data.records.Table
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tick on this tablet's own fallback plan (the plan made on the tablet when the server has not sent one yet).
 * Private to this tablet and never sent to the server, so it cannot create an empty DailyPlan row there.
 * id = "<date>|<block id>".
 */
@Serializable
data class PlanTick(
    val id: String,
    val date: String = "",
    @SerialName("block_id") val blockId: String = "",
    val status: String = "done",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

object LocalPlanTables {
    val Ticks = Table("local_plan_ticks", PlanTick.serializer(), { it.id }, k1 = "date", localOnly = true)
}
