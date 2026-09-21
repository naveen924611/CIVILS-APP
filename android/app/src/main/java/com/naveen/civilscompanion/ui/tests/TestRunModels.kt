package com.naveen.civilscompanion.ui.tests

import com.naveen.civilscompanion.data.records.Table
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The state of one test being taken on this tablet (answers, timer start, flags). Kept in a private table so a test survives
 * closing the app and a mistakes retest (which is not a server test) has somewhere to keep its answers.
 * id = the test id, or "mistakes" for the mistakes retest. startedAtMs 0 = not started yet.
 */
@Serializable
data class LocalTestRun(
    val id: String,
    val title: String = "",
    val kind: String = "",
    @SerialName("question_ids") val questionIds: List<String> = emptyList(),
    @SerialName("duration_min") val durationMin: Int = 30,
    val negative: Boolean = false,
    @SerialName("started_at_ms") val startedAtMs: Long = 0,
    @SerialName("finished_at_ms") val finishedAtMs: Long = 0,
    val index: Int = 0,
    val chosen: Map<String, Int> = emptyMap(),
    val confidence: Map<String, String> = emptyMap(),
    @SerialName("mistake_types") val mistakeTypes: Map<String, String> = emptyMap(),
    val flagged: List<String> = emptyList(),
    val finished: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

object TestLocalTables {
    val Runs = Table("local_test_runs", LocalTestRun.serializer(), { it.id }, localOnly = true)
}

/** The id used for the mistakes retest (route test/mistakes). */
const val RETEST_ID = "mistakes"
