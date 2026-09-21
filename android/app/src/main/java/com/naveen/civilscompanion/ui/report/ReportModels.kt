package com.naveen.civilscompanion.ui.report

import com.naveen.civilscompanion.data.records.RecordJson
import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* The shape of WeeklyReport.data_json (see backend/app/features/reports/weekly.py). Every field has a default. */

@Serializable
data class DayHours(val date: String = "", val day: String = "", val planned: Int = 0, val done: Int = 0)

@Serializable
data class HoursData(
    @SerialName("planned_minutes") val plannedMinutes: Int = 0,
    @SerialName("done_minutes") val doneMinutes: Int = 0,
    @SerialName("focus_minutes") val focusMinutes: Int = 0,
    @SerialName("by_day") val byDay: List<DayHours> = emptyList(),
)

@Serializable
data class TopicRef(@SerialName("topic_id") val topicId: String = "", val title: String = "", val subject: String = "")

@Serializable
data class CardsData(val revised: Int = 0, val distinct: Int = 0, val fading: Int = 0)

@Serializable
data class TestScore(val title: String = "", val score: Double? = null, val total: Int = 0)

@Serializable
data class McqData(val attempted: Int = 0, val correct: Int = 0, val accuracy: Double = 0.0, val tests: List<TestScore> = emptyList())

@Serializable
data class WeakTopic(@SerialName("topic_id") val topicId: String = "", val title: String = "", val strength: Double = 0.0)

@Serializable
data class WeakData(
    val topics: List<WeakTopic> = emptyList(),
    @SerialName("fading_cards") val fadingCards: Int = 0,
    @SerialName("low_days") val lowDays: List<String> = emptyList(),
)

@Serializable
data class Adjustments(
    @SerialName("week_start") val weekStart: String = "",
    @SerialName("extra_revision_minutes") val extraRevisionMinutes: Int = 0,
    @SerialName("focus_topic_ids") val focusTopicIds: List<String> = emptyList(),
    @SerialName("reduce_new_topics") val reduceNewTopics: Boolean = false,
)

@Serializable
data class NextWeek(
    @SerialName("week_start") val weekStart: String = "",
    val adjustments: Adjustments = Adjustments(),
    val changes: List<String> = emptyList(),
)

@Serializable
data class LastMonth(val active: Boolean = false, val exam: String = "", @SerialName("days_left") val daysLeft: Int? = null)

@Serializable
data class ReportData(
    @SerialName("week_start") val weekStart: String = "",
    @SerialName("week_end") val weekEnd: String = "",
    val narrative: String = "",
    val script: String = "",
    val hours: HoursData = HoursData(),
    @SerialName("topics_finished") val topicsFinished: List<TopicRef> = emptyList(),
    val covered: List<TopicRef> = emptyList(),
    val cards: CardsData = CardsData(),
    val mcq: McqData = McqData(),
    @SerialName("weak_spots") val weakSpots: WeakData = WeakData(),
    @SerialName("next_week") val nextWeek: NextWeek = NextWeek(),
    @SerialName("last_month") val lastMonth: LastMonth = LastMonth(),
)

/** Pure helpers of the report screen (no Android classes). */
object ReportLogic {
    /** Reads a report's data; an unreadable one gives an empty report instead of a crash. */
    fun parse(data: JsonObject): ReportData =
        runCatching { RecordJson.decodeFromJsonElement(ReportData.serializer(), data) }.getOrDefault(ReportData())

    /** Share of the planned time that was done, 0 to 100 (0 when nothing was planned). */
    fun donePercent(h: HoursData): Int = if (h.plannedMinutes <= 0) 0 else Math.min(100, Math.round(h.doneMinutes * 100.0 / h.plannedMinutes).toInt())

    fun hoursText(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h h"
            else -> "$h h $m min"
        }
    }

    fun dayLabel(key: String): String = when (key) {
        "mon" -> "Mon"
        "tue" -> "Tue"
        "wed" -> "Wed"
        "thu" -> "Thu"
        "fri" -> "Fri"
        "sat" -> "Sat"
        "sun" -> "Sun"
        else -> key
    }

    /** The Monday (YYYY-MM-DD) of the week that holds [today]. */
    fun mondayOf(today: String): String {
        val d = LocalDate.parse(today)
        return d.minusDays((d.dayOfWeek.value - 1).toLong()).toString()
    }

    /** The value stored in KV plan.adjustments when the owner accepts next week's plan (build guide 9.1). */
    fun adjustmentsJson(a: Adjustments, nextMonday: String): JsonObject = JsonObject(
        mapOf(
            "week_start" to JsonPrimitive(a.weekStart.ifBlank { nextMonday }),
            "extra_revision_minutes" to JsonPrimitive(a.extraRevisionMinutes),
            "focus_topic_ids" to JsonArray(a.focusTopicIds.map { JsonPrimitive(it) }),
            "reduce_new_topics" to JsonPrimitive(a.reduceNewTopics),
        ),
    )

    /** True when the stored plan.adjustments is for this week already. */
    fun isAccepted(stored: JsonElement?, weekStart: String): Boolean {
        val obj = stored as? JsonObject ?: return false
        val start = (obj["week_start"] as? JsonPrimitive)?.content
        return start != null && start == weekStart
    }

    /** A one-line title for a report week: "14 Sep to 20 Sep". */
    fun weekTitle(weekStart: String, weekEnd: String): String {
        fun short(text: String): String = runCatching {
            val d = LocalDate.parse(text)
            "${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
        }.getOrDefault(text)
        return if (weekEnd.isBlank()) short(weekStart) else "${short(weekStart)} to ${short(weekEnd)}"
    }
}
