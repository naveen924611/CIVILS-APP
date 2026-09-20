package com.naveen.civilscompanion.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * One row of any synced table (topics, notes, cards, ...). The whole row is kept as JSON so new fields never
 * need a database change. k1/k2/n1/text are copies of a few values, kept for fast filtering and search.
 * dirty = changed on this tablet and not yet accepted by the server. deleted = tombstone waiting to be pushed.
 */
@Entity(
    tableName = "records",
    primaryKeys = ["tbl", "id"],
    indices = [
        Index(value = ["tbl", "k1"]),
        Index(value = ["tbl", "k2"]),
        Index(value = ["tbl", "n1"]),
        Index(value = ["dirty"]),
    ],
)
data class RecordEntity(
    val tbl: String,
    val id: String,
    val json: String,
    val updatedAt: String,
    val dirty: Boolean,
    val deleted: Boolean,
    val k1: String?,
    val k2: String?,
    val n1: Double?,
    val text: String,
)
