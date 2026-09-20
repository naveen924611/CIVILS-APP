package com.naveen.civilscompanion.data.records

import androidx.room.withTransaction
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.local.RecordDao
import com.naveen.civilscompanion.data.local.RecordEntity
import com.naveen.civilscompanion.sync.SyncTrigger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Reading rules shared by the whole app: unknown fields are ignored, missing ones fall back to defaults. */
val RecordJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

enum class Order(val code: Int) {
    None(0),
    NumberAsc(1),
    NumberDesc(2),
    NewestFirst(3),
    Key1AZ(4),
    TextAZ(5),
    OldestFirst(6),
}

/**
 * Filter for lists. All parts are optional and combined with AND.
 *  k1, k2: exact match on the table's indexed keys (see Tables.kt)
 *  numberMin/numberMax: range on the table's n1 (dates are epoch milliseconds, yes/no is 1 or 0)
 *  contains: words to find in the table's searchable text
 */
data class RecordQuery(
    val k1: String? = null,
    val k2: String? = null,
    val numberMin: Double? = null,
    val numberMax: Double? = null,
    val contains: String? = null,
    val order: Order = Order.None,
    val limit: Int = 5000,
) {
    internal fun like(): String? = contains?.trim()?.takeIf { it.isNotEmpty() }?.let {
        "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}

/**
 * The one place screens read and write synced data. Writes are saved locally at once (so the app works offline),
 * marked "dirty", and pushed to the server by the next sync. Reads come back as Flow so screens update themselves.
 */
@Singleton
class RecordStore @Inject constructor(
    private val db: AppDatabase,
    private val dao: RecordDao,
    private val trigger: SyncTrigger,
) {
    // ------------------------------------------------------------------ reading

    fun <T : Any> observe(table: Table<T>, query: RecordQuery = RecordQuery()): Flow<List<T>> =
        dao.observe(
            table.name, query.k1, query.k2, query.numberMin, query.numberMax, query.like(), query.order.code, query.limit,
        ).map { rows -> rows.mapNotNull { decode(table, it) } }

    suspend fun <T : Any> list(table: Table<T>, query: RecordQuery = RecordQuery()): List<T> =
        dao.query(
            table.name, query.k1, query.k2, query.numberMin, query.numberMax, query.like(), query.order.code, query.limit,
        ).mapNotNull { decode(table, it) }

    fun <T : Any> observeOne(table: Table<T>, id: String): Flow<T?> =
        dao.observeOne(table.name, id).map { row -> row?.let { decode(table, it) } }

    suspend fun <T : Any> get(table: Table<T>, id: String): T? =
        dao.get(table.name, id)?.takeIf { !it.deleted }?.let { decode(table, it) }

    fun observeCount(table: Table<*>, query: RecordQuery = RecordQuery()): Flow<Int> =
        dao.observeCount(table.name, query.k1, query.k2, query.numberMin, query.numberMax)

    suspend fun count(table: Table<*>, query: RecordQuery = RecordQuery()): Int =
        dao.count(table.name, query.k1, query.k2, query.numberMin, query.numberMax)

    // ------------------------------------------------------------------ writing

    /** Creates or updates a row. Returns its id. */
    suspend fun <T : Any> save(table: Table<T>, value: T): String {
        val id = table.idOf(value)
        db.withTransaction { writeOne(table, id, value) }
        trigger.request()
        return id
    }

    suspend fun <T : Any> saveAll(table: Table<T>, values: List<T>) {
        if (values.isEmpty()) return
        db.withTransaction { values.forEach { writeOne(table, table.idOf(it), it) } }
        trigger.request()
    }

    /** Reads the row, applies `change`, saves it. Returns the new value, or null if the row does not exist. */
    suspend fun <T : Any> update(table: Table<T>, id: String, change: (T) -> T): T? {
        var result: T? = null
        db.withTransaction {
            val current = dao.get(table.name, id)?.takeIf { !it.deleted }?.let { decode(table, it) }
            if (current != null) {
                val next = change(current)
                writeOne(table, id, next)
                result = next
            }
        }
        if (result != null) trigger.request()
        return result
    }

    /** Deletes a row on this tablet and on the server (the server keeps a tombstone so other devices agree). */
    suspend fun delete(table: Table<*>, id: String) {
        db.withTransaction {
            val row = dao.get(table.name, id) ?: return@withTransaction
            if (table.localOnly) {
                dao.delete(table.name, listOf(id))
                return@withTransaction
            }
            val now = TimeUtil.nowIso()
            val obj = parse(row.json).toMutableMap()
            obj["deleted"] = JsonPrimitive(true)
            obj["updated_at"] = JsonPrimitive(now)
            dao.upsertOne(row.copy(json = JsonObject(obj).toString(), updatedAt = now, dirty = !table.localOnly, deleted = true))
        }
        trigger.request()
    }

    // ------------------------------------------------------------------ internals

    private suspend fun <T : Any> writeOne(table: Table<T>, id: String, value: T) {
        val now = TimeUtil.nowIso()
        val encoded = RecordJson.encodeToJsonElement(table.serializer, value) as JsonObject
        val existing = dao.get(table.name, id)?.let { parse(it.json) } ?: JsonObject(emptyMap())
        // keep server-only fields this app's model does not know about
        val merged = HashMap<String, JsonElement>(existing)
        merged.putAll(encoded)
        merged["id"] = JsonPrimitive(id)
        merged["updated_at"] = JsonPrimitive(now)
        merged["deleted"] = JsonPrimitive(false)
        val obj = JsonObject(merged)
        val idx = table.index(obj)
        dao.upsertOne(
            RecordEntity(
                tbl = table.name, id = id, json = obj.toString(), updatedAt = now, dirty = !table.localOnly, deleted = false,
                k1 = idx.k1, k2 = idx.k2, n1 = idx.n1, text = idx.text,
            ),
        )
    }

    private fun <T : Any> decode(table: Table<T>, row: RecordEntity): T? =
        runCatching { RecordJson.decodeFromString(table.serializer, row.json) }.getOrNull()

    private fun parse(text: String): JsonObject =
        runCatching { RecordJson.parseToJsonElement(text) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
}
