package com.naveen.civilscompanion.data

import android.content.Context
import android.content.SharedPreferences
import com.naveen.civilscompanion.data.remote.dto.BriefSettingsDto
import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/** Small settings that live only on this tablet. */
@Singleton
class Prefs @Inject constructor(@ApplicationContext context: Context, private val json: Json) {
    private val p: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var lastSync: String?
        get() = p.getString("last_sync", null)
        set(v) = p.edit().putString("last_sync", v).apply()

    var wifiOnlyDownloads: Boolean
        get() = p.getBoolean("wifi_only", false)
        set(v) = p.edit().putBoolean("wifi_only", v).apply()

    var playbackSpeed: Float
        get() = p.getFloat("speed", 1.0f)
        set(v) = p.edit().putFloat("speed", v).apply()

    var setupDone: Boolean
        get() = p.getBoolean("setup_done", false)
        set(v) = p.edit().putBoolean("setup_done", v).apply()

    /** Brief times as last saved on the server (copied here so alarms work offline). */
    var briefSettings: BriefSettingsDto
        get() = p.getString("brief_settings", null)
            ?.let { runCatching { json.decodeFromString(BriefSettingsDto.serializer(), it) }.getOrNull() }
            ?: DEFAULT_BRIEFS
        set(v) = p.edit().putString("brief_settings", json.encodeToString(BriefSettingsDto.serializer(), v)).apply()

    /** Briefs we have already shown a notification for (so a dismissed one is never shown again). */
    private fun notifiedIds(): List<String> =
        p.getString("notified", "").orEmpty().split(',').filter { it.isNotBlank() }

    fun wasNotified(briefId: String) = briefId in notifiedIds()

    fun markNotified(briefId: String) {
        // oldest first, keep the newest 40
        val ids = (notifiedIds() + briefId).takeLast(40)
        p.edit().putString("notified", ids.joinToString(",")).apply()
    }

    companion object {
        val DEFAULT_BRIEFS = BriefSettingsDto(
            listOf(BriefSlotDto("morning", "07:00"), BriefSlotDto("evening", "19:00")),
        )
    }
}
