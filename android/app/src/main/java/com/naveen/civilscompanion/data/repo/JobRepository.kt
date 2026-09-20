package com.naveen.civilscompanion.data.repo

import com.naveen.civilscompanion.data.model.Job
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The offline job queue. A job is a request for the server's AI or heavy processing (an answer to a question,
 * feedback on an explanation, OCR of a page, ...). It is saved on the tablet at once and sent when there is a
 * network, so it works offline. When the server finishes, the row changes to done and the result is in `result`.
 *
 * Job types the server knows are listed in docs/job-types.md.
 */
@Singleton
class JobRepository @Inject constructor(private val store: RecordStore) {

    /** Queues a job. Returns its id at once (it is sent to the server when the network allows). */
    suspend fun enqueue(type: String, payload: JsonObject = JsonObject(emptyMap())): String {
        val job = Job(
            id = TimeUtil.newId(), type = type, payload = payload, status = "queued", createdAt = TimeUtil.nowIso(),
        )
        return store.save(Tables.Jobs, job)
    }

    fun observe(id: String): Flow<Job?> = store.observeOne(Tables.Jobs, id)

    /** Jobs still waiting for an answer (queued or running). */
    fun observeWaiting(): Flow<Int> = combine(
        store.observeCount(Tables.Jobs, RecordQuery(k2 = "queued")),
        store.observeCount(Tables.Jobs, RecordQuery(k2 = "running")),
    ) { a, b -> a + b }

    fun observeRecent(type: String? = null, limit: Int = 50): Flow<List<Job>> =
        store.observe(Tables.Jobs, RecordQuery(k1 = type, order = com.naveen.civilscompanion.data.records.Order.NewestFirst, limit = limit))

    /** Waits (up to timeoutMs) until the job is done or failed. Returns null on timeout. */
    suspend fun await(id: String, timeoutMs: Long = 120_000): Job? = withTimeoutOrNull(timeoutMs) {
        observe(id).first { it != null && (it.status == "done" || it.status == "failed") }
    }
}

/** Builds a job payload: jobPayload("question" to "What is FRBM?", "topic_ids" to listOf("t1")). */
fun jobPayload(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is String -> JsonPrimitive(v)
            is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) })
            is kotlinx.serialization.json.JsonElement -> v
            else -> JsonPrimitive(v.toString())
        }
    },
)
