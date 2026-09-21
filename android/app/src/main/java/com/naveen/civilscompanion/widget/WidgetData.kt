package com.naveen.civilscompanion.widget

import android.content.Context
import com.naveen.civilscompanion.appEntryPoint
import com.naveen.civilscompanion.data.BriefTimes
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.ui.today.PlanBlocks
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonPrimitive

/** Reads the tablet's own data (works offline) and turns it into the widget's model. Never throws. */
object WidgetData {
    suspend fun load(context: Context): WidgetModel {
        return try {
            val app = context.appEntryPoint()
            val store = app.records()
            val today = TimeUtil.today()
            val plan = store.list(Tables.DailyPlans, RecordQuery(k1 = today)).firstOrNull()
            val blocks = plan?.let { PlanBlocks.parseAll(it.blocks) }.orEmpty()
            val completion = plan?.completion.orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
            val now = System.currentTimeMillis()
            val due = store.count(Tables.Cards, RecordQuery(numberMax = now.toDouble()))
            val dayStart = TimeUtil.startOfDay(today).toDouble()
            val studied = store.list(Tables.FocusSessions, RecordQuery(numberMin = dayStart)).sumOf { it.minutes }
            WidgetLogic.build(blocks, completion, due, studied, nextBriefText(app.prefs().briefSettings.briefs))
        } catch (e: Exception) {
            WidgetLogic.build(emptyList(), emptyMap(), 0, 0, "")
        }
    }

    private fun nextBriefText(slots: List<com.naveen.civilscompanion.data.remote.dto.BriefSlotDto>): String {
        val now = ZonedDateTime.now()
        val best = slots.mapNotNull { slot -> BriefTimes.nextOccurrence(slot, now)?.let { slot to it } }.minByOrNull { it.second.toInstant() } ?: return ""
        return "${BriefTimes.label(best.first.id)} ${BriefTimes.display(best.first.time)}"
    }
}
