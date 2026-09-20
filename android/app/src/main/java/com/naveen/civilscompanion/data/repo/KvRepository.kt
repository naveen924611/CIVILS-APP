package com.naveen.civilscompanion.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.naveen.civilscompanion.data.remote.SyncApi
import com.naveen.civilscompanion.data.remote.dto.KvPutDto
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Owner settings shared with the server (study hours, revision limits, DND choices, ...), keyed like "study.hours".
 * Local first: reads come from this tablet (so the app works offline); writes are saved here at once and sent
 * to the server, and retried by the next sync if the network is down.
 */
@Singleton
class KvRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val api: SyncApi,
    private val json: Json,
) {
    private val p: SharedPreferences = context.getSharedPreferences("kv", Context.MODE_PRIVATE)
    private val values = MutableStateFlow(load())

    private fun load(): Map<String, JsonElement> =
        p.all.mapNotNull { (k, v) ->
            if (k == PENDING || v !is String) null
            else runCatching { k to json.parseToJsonElement(v) }.getOrNull()
        }.toMap()

    private fun pending(): Set<String> = p.getString(PENDING, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun observe(key: String): Flow<JsonElement?> = values.map { it[key] }.distinctUntilChanged()

    fun get(key: String): JsonElement? = values.value[key]

    fun <T> get(key: String, serializer: KSerializer<T>, default: T): T =
        get(key)?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() } ?: default

    fun <T> observe(key: String, serializer: KSerializer<T>, default: T): Flow<T> =
        observe(key).map { e -> e?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() } ?: default }

    /** Saves a setting. Returns true when the server has it too (false = saved here, will be sent later). */
    suspend fun put(key: String, value: JsonElement): Boolean {
        p.edit().putString(key, value.toString()).putString(PENDING, (pending() + key).joinToString(",")).apply()
        values.value = values.value + (key to value)
        return flushOne(key)
    }

    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T): Boolean =
        put(key, json.encodeToJsonElement(serializer, value))

    /** Sends settings that could not be sent before. Called by every sync. */
    suspend fun flushPending() {
        pending().forEach { flushOne(it) }
    }

    /** Fetches all settings from the server (keeps the ones still waiting to be sent). */
    suspend fun refreshAll() {
        val remote = api.kvAll()
        val waiting = pending()
        val edit = p.edit()
        val merged = values.value.toMutableMap()
        for ((k, v) in remote) {
            if (k in waiting) continue
            edit.putString(k, v.toString())
            merged[k] = v
        }
        edit.apply()
        values.value = merged
    }

    private suspend fun flushOne(key: String): Boolean {
        val value = values.value[key] ?: return true
        return try {
            api.kvPut(key, KvPutDto(value))
            p.edit().putString(PENDING, (pending() - key).joinToString(",")).apply()
            true
        } catch (e: java.io.IOException) {
            false
        } catch (e: retrofit2.HttpException) {
            false
        }
    }

    /** Forget everything (used on logout). */
    fun clear() {
        p.edit().clear().apply()
        values.value = emptyMap()
    }

    private companion object {
        const val PENDING = "__pending"
    }
}
