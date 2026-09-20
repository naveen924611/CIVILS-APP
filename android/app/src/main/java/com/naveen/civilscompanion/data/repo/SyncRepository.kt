package com.naveen.civilscompanion.data.repo

import androidx.room.withTransaction
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.records.RecordSync
import com.naveen.civilscompanion.data.remote.SyncApi
import com.naveen.civilscompanion.data.remote.dto.BriefSettingsDto
import com.naveen.civilscompanion.data.remote.dto.RunBriefRequest
import com.naveen.civilscompanion.data.remote.dto.SyncPullDto
import com.naveen.civilscompanion.data.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Copies what the server has that this tablet does not, and keeps it in the local database. */
@Singleton
class SyncRepository @Inject constructor(
    private val api: SyncApi,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val json: Json,
    private val records: RecordSync,
    private val kv: KvRepository,
) {
    /** Sends this tablet's changes, then fetches the server's. Returns how many rows came down. */
    suspend fun sync(): Int {
        // A refused push (for example one bad row) must not stop briefs and alerts from arriving.
        var pushProblem: Exception? = null
        try {
            records.pushDirty()
        } catch (e: HttpException) {
            pushProblem = e
        } catch (e: SerializationException) {
            pushProblem = e
        }
        runCatching { kv.flushPending() }
        val changed = pull()
        runCatching { kv.refreshAll() }
        pushProblem?.let { throw it } // let WorkManager retry the push later
        return changed
    }

    /** Pulls every change since the last sync. Returns how many rows changed. Throws on network errors. */
    suspend fun pull(): Int {
        var since = prefs.lastSync
        var firstServerTime: String? = null
        var changed = 0
        var rounds = 0
        var finished = false
        while (rounds++ < MAX_ROUNDS) {
            val page = api.pull(since)
            if (firstServerTime == null) firstServerTime = page.serverTime
            changed += applyPage(page)
            if (!page.more) {
                finished = true
                break
            }
            since = page.nextSince ?: break
        }
        // Everything received: next time start from the first answer's time. Otherwise carry on from where we stopped.
        val next = if (finished) firstServerTime else since
        if (next != null) prefs.lastSync = next
        return changed
    }

    private suspend fun applyPage(page: SyncPullDto): Int = db.withTransaction {
        val items = page.newsItems
        db.newsItems().upsert(items.filter { !it.deleted }.map { it.toEntity(json) })
        deleteChunked(items.filter { it.deleted }.map { it.id }) { db.newsItems().delete(it) }

        db.briefs().upsert(page.briefs.filter { !it.deleted }.map { it.toEntity(json) })
        deleteChunked(page.briefs.filter { it.deleted }.map { it.id }) { db.briefs().delete(it) }

        db.alerts().upsert(page.alerts.filter { !it.deleted }.map { it.toEntity(json) })
        deleteChunked(page.alerts.filter { it.deleted }.map { it.id }) { db.alerts().delete(it) }

        items.size + page.briefs.size + page.alerts.size + records.applyPulled(page.tables)
    }

    private suspend fun deleteChunked(ids: List<String>, delete: suspend (List<String>) -> Unit) {
        ids.chunked(400).forEach { delete(it) }
    }

    suspend fun refreshBriefSettings(): BriefSettingsDto {
        val fresh = api.briefSettings()
        prefs.briefSettings = fresh
        return fresh
    }

    suspend fun saveBriefSettings(value: BriefSettingsDto): BriefSettingsDto {
        val saved = api.putBriefSettings(value)
        prefs.briefSettings = saved
        return saved
    }

    /** Asks the server to prepare a brief now. Returns the new brief's id. */
    suspend fun prepareBriefNow(): String = api.runBrief(RunBriefRequest("extra1")).briefId

    suspend fun markAlertRead(id: String) {
        db.alerts().markRead(id)
        runCatching { api.markAlertRead(id) }
    }

    suspend fun markAllAlertsRead() {
        db.alerts().markAllRead()
        runCatching { api.markAllAlertsRead() }
    }

    companion object {
        private const val MAX_ROUNDS = 40
    }
}
