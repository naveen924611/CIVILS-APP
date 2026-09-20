package com.naveen.civilscompanion.data.records

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** What the local database indexes for one row (so screens can filter and sort without reading every row). */
data class RecordIndex(val k1: String?, val k2: String?, val n1: Double?, val text: String)

/**
 * Describes one synced table: its name on the server, how to read/write it, and which JSON keys are indexed.
 *  - k1, k2: text keys you can filter on with RecordQuery(k1 = ..., k2 = ...)
 *  - n1: a number, yes/no, or ISO time (stored as epoch milliseconds) for range filters and sorting
 *  - text: JSON keys searched by RecordQuery(contains = "...")
 */
class Table<T : Any>(
    val name: String,
    val serializer: KSerializer<T>,
    val idOf: (T) -> String,
    val k1: String? = null,
    val k2: String? = null,
    val n1: String? = null,
    val text: List<String> = emptyList(),
) {
    fun index(obj: JsonObject): RecordIndex = RecordIndex(
        k1 = k1?.let { textOf(obj[it]) },
        k2 = k2?.let { textOf(obj[it]) },
        n1 = n1?.let { numberOf(obj[it]) },
        text = text.mapNotNull { textOf(obj[it]) }.joinToString("\n"),
    )

    companion object {
        fun textOf(e: JsonElement?): String? = when (e) {
            null, JsonNull -> null
            is JsonPrimitive -> e.content
            is JsonArray -> e.mapNotNull { textOf(it) }.joinToString(" ")
            else -> e.toString()
        }

        fun numberOf(e: JsonElement?): Double? {
            if (e !is JsonPrimitive || e is JsonNull) return null
            e.booleanOrNull?.let { return if (it) 1.0 else 0.0 }
            e.doubleOrNull?.let { return it }
            return runCatching { Instant.parse(e.content).toEpochMilli().toDouble() }.getOrNull()
        }
    }
}
