package com.naveen.civilscompanion.data.records

import androidx.room.withTransaction
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.local.RecordDao
import com.naveen.civilscompanion.data.local.RecordEntity
import com.naveen.civilscompanion.data.remote.SyncApi
import com.naveen.civilscompanion.data.remote.dto.PushBodyDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Sends this tablet's changes to the server and stores the server's changes. Both directions are safe to repeat. */
@Singleton
class RecordSync @Inject constructor(
    private val api: SyncApi,
    private val db: AppDatabase,
    private val dao: RecordDao,
) {
    /**
     * Pushes every "dirty" row (in batches). Returns how many rows the server accepted.
     * Rows the server refuses for good (unknown or read-only table, bad data) are marked clean so they never block sync.
     */
    suspend fun pushDirty(): Int {
        var accepted = 0
        var rounds = 0
        while (rounds++ < MAX_PUSH_ROUNDS) {
            val rows = dao.dirtyRows(BATCH)
            if (rows.isEmpty()) break
            val body = PushBodyDto(
                rows.groupBy { it.tbl }.mapValues { (_, list) -> list.mapNotNull { asObject(it.json) } },
            )
            val result = api.push(body)
            val byKey = rows.associateBy { it.tbl to it.id }
            db.withTransaction {
                val done = result.accepted.flatMap { (table, ids) -> ids.map { table to it } } +
                    result.rejected.map { it.table to it.id }
                for (key in done) {
                    val row = byKey[key] ?: continue
                    if (row.deleted) dao.purgeDeleted(row.tbl, row.id, row.updatedAt)
                    else dao.markClean(row.tbl, row.id, row.updatedAt)
                }
            }
            accepted += result.accepted.values.sumOf { it.size }
            // rows the server did not mention stay dirty; stop if nothing at all was cleared (avoid a loop)
            if (result.accepted.isEmpty() && result.rejected.isEmpty()) break
        }
        return accepted
    }

    /** Stores rows from GET /sync/pull "tables". Returns how many rows were looked at. */
    suspend fun applyPulled(tables: Map<String, List<JsonObject>>): Int {
        var count = 0
        db.withTransaction {
            for ((name, rows) in tables) {
                val table = Tables.all[name] ?: continue // a table this app version does not know yet
                val toDelete = ArrayList<String>()
                val toWrite = ArrayList<RecordEntity>()
                for (obj in rows) {
                    val id = (obj["id"] as? JsonPrimitive)?.content ?: continue
                    count++
                    val updatedAt = (obj["updated_at"] as? JsonPrimitive)?.content.orEmpty()
                    val deleted = (obj["deleted"] as? JsonPrimitive)?.booleanOrNull == true
                    val local = dao.get(name, id)
                    if (local != null && local.dirty && isNotOlder(local.updatedAt, updatedAt)) continue // my edit wins
                    if (deleted) {
                        toDelete.add(id)
                        continue
                    }
                    val idx = table.index(obj)
                    toWrite.add(
                        RecordEntity(
                            tbl = name, id = id, json = obj.toString(), updatedAt = updatedAt, dirty = false,
                            deleted = false, k1 = idx.k1, k2 = idx.k2, n1 = idx.n1, text = idx.text,
                        ),
                    )
                }
                if (toWrite.isNotEmpty()) dao.upsert(toWrite)
                toDelete.chunked(400).forEach { dao.delete(name, it) }
            }
        }
        return count
    }

    private fun isNotOlder(local: String, server: String): Boolean {
        val l = TimeUtil.parse(local) ?: return false
        val s = TimeUtil.parse(server) ?: return true
        return l >= s
    }

    private fun asObject(text: String): JsonObject? =
        runCatching { RecordJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

    companion object {
        private const val BATCH = 200
        private const val MAX_PUSH_ROUNDS = 25
    }
}
