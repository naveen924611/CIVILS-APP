package com.naveen.civilscompanion.reader

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Where the owner stopped in a document: stored in the document's `reading_position` as {"page":3,"sentence":12}. */
data class ReadingPosition(val page: Int = 1, val sentence: Int = 0) {
    fun toJson(): JsonObject = JsonObject(mapOf("page" to JsonPrimitive(page), "sentence" to JsonPrimitive(sentence)))

    companion object {
        fun from(obj: JsonObject?): ReadingPosition {
            if (obj == null) return ReadingPosition()
            val page = (obj["page"] as? JsonPrimitive)?.intOrNull ?: 1
            val sentence = (obj["sentence"] as? JsonPrimitive)?.intOrNull ?: 0
            return ReadingPosition(page.coerceAtLeast(1), sentence.coerceAtLeast(0))
        }
    }
}

/** Reading progress 0..100 for a page number in a document of [pages] pages. */
fun readingPercent(page: Int, pages: Int): Int =
    if (pages <= 0) 0 else ((page.coerceIn(1, pages) * 100.0) / pages).toInt().coerceIn(0, 100)
