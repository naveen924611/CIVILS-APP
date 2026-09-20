package com.naveen.civilscompanion.data.repo

import androidx.room.withTransaction
import com.naveen.civilscompanion.data.Prefs
import com.naveen.civilscompanion.data.local.AppDatabase
import com.naveen.civilscompanion.data.remote.SyncApi
import com.naveen.civilscompanion.data.remote.dto.BriefSettingsDto
import com.naveen.civilscompanion.data.remote.dto.RunBriefRequest
import com.naveen.civilscompanion.data.remote.dto.SyncPullDto
import com.naveen.civilscompanion.data.toEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Copies what the server has that this tablet does not, and keeps it in the local database. */
@Singleton
class SyncRepository @Inject constructor(
    private val api: SyncApi,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val json: Json,
) {
    /** Pulls every change since the last sync. Returns how many rows changed. Throws on network errors. */
    suspend fun pull(): Int {
        var since = prefs.lastSync
        var firstServerTime: String? = null
        var changed = 0
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val page = api.pull(since)
            if (firstServerTime == null) firstServerTime = page.serverTime
            changed += applyPage(page)
            if (!page.more) break
            since = nextCursor(page) ?: break
        }
        firstServerTime?.let { prefs.lastSync = it }
        return changed
    }

    private suspend fun applyPage(page: SyncPullDto): Int = db.withTransaction {
        val items = page.newsItems
        db.newsItems().upsert(items.filter { !it.deleted }.map { it.toEntity(json) })
        deleteChunked(items.filter { it.deleted }.map { it.id }) { db.newsItems().delete(it) }

        db.briefs().upsert(page.briefs.filter { !it.deleted }.map { it.toEntity(json) })
        deleteChunked(page.briefs.filter { it.deleted }.map { it.id }) { db.briefs().delete(it) }

        db.cards().upsert(page.cards.filter { !it.deleted }.map { it.toEntity() })
        deleteChunked(page.cards.filter { it.deleted }.map { it.id }) { db.cards().delete(it) }

        db.alerts().upsert(page.alerts.filter { !it.deleted }.map { it.toEntity(json) })
        deleteChunked(page.alerts.filter { it.deleted }.map { it.id }) { db.alerts().delete(it) }

        items.size + page.briefs.size + page.cards.size + page.alerts.size
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
        private const val MAX_ROUNDS = 20

        /** When a page was full, continue from the oldest "last row" among the full lists. */
        fun nextCursor(page: SyncPullDto): String? {
            val full = 500
            val ends = buildList {
                if (page.newsItems.size >= full) add(page.newsItems.last().updatedAt)
                if (page.briefs.size >= full) add(page.briefs.last().updatedAt)
                if (page.cards.size >= full) add(page.cards.last().updatedAt)
                if (page.alerts.size >= full) add(page.alerts.last().updatedAt)
            }
            return ends.minOrNull()
        }
    }
}
