package com.naveen.civilscompanion.data.repo

import com.naveen.civilscompanion.data.decodeStrings
import com.naveen.civilscompanion.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Saves the audio of recent briefs on the tablet. */
@Singleton
class DownloadRepository @Inject constructor(
    private val db: AppDatabase,
    private val audio: AudioStore,
    private val json: Json,
) {
    /** Downloads audio for the newest [count] ready briefs. Returns the ids of briefs that are now fully saved. */
    suspend fun downloadRecent(count: Int = 3): Set<String> {
        val since = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val complete = mutableSetOf<String>()
        for (brief in db.briefs().readySince(since).take(count)) {
            val ids = decodeStrings(json, brief.itemIdsJson)
            val items = db.newsItems().get(ids)
            var all = true
            for (item in items) {
                val url = item.audioUrl ?: continue
                if (!audio.download(item.id, url)) all = false
            }
            if (all) complete += brief.id
        }
        audio.deleteOlderThan(14)
        return complete
    }
}
