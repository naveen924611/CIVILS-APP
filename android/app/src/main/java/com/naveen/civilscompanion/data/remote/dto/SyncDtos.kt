package com.naveen.civilscompanion.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class FactDto(val q: String, val a: String)

@Serializable
data class McqDto(
    val question: String,
    val options: List<String> = emptyList(),
    @SerialName("answer_index") val answerIndex: Int = 0,
    val explanation: String = "",
)

@Serializable
data class NewsItemDto(
    val id: String,
    val url: String,
    val source: String = "",
    val title: String,
    val summary: String = "",
    @SerialName("relevance_upsc") val relevanceUpsc: Int = 0,
    @SerialName("relevance_appsc") val relevanceAppsc: Int = 0,
    val papers: List<String> = emptyList(),
    @SerialName("prelims_facts") val prelimsFacts: List<FactDto> = emptyList(),
    @SerialName("mains_angle") val mainsAngle: String = "",
    val keywords: List<String> = emptyList(),
    @SerialName("is_ap_specific") val isApSpecific: Boolean = false,
    val mcqs: List<McqDto> = emptyList(),
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("audio_url") val audioUrl: String? = null,
    @SerialName("audio_seconds") val audioSeconds: Int? = null,
    @SerialName("brief_id") val briefId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class BriefDto(
    val id: String,
    val kind: String,
    @SerialName("scheduled_for") val scheduledFor: String,
    val status: String,
    @SerialName("item_ids") val itemIds: List<String> = emptyList(),
    @SerialName("audio_seconds_total") val audioSecondsTotal: Int = 0,
    val note: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class AlertDto(
    val id: String,
    val kind: String,
    val title: String,
    val body: String = "",
    val payload: JsonObject = JsonObject(emptyMap()),
    val read: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class SyncPullDto(
    @SerialName("server_time") val serverTime: String,
    /** Where the next request should start (the same as server_time once everything has been received). */
    @SerialName("next_since") val nextSince: String? = null,
    val more: Boolean = false,
    @SerialName("news_items") val newsItems: List<NewsItemDto> = emptyList(),
    val briefs: List<BriefDto> = emptyList(),
    val alerts: List<AlertDto> = emptyList(),
    /** table name -> changed rows (see data/records/Tables.kt) */
    val tables: Map<String, List<JsonObject>> = emptyMap(),
)

@Serializable
data class PushBodyDto(val tables: Map<String, List<JsonObject>>)

@Serializable
data class PushRejectDto(val table: String = "", val id: String = "", val reason: String = "")

@Serializable
data class PushResultDto(
    val accepted: Map<String, List<String>> = emptyMap(),
    val rejected: List<PushRejectDto> = emptyList(),
    @SerialName("server_time") val serverTime: String = "",
)

@Serializable
data class KvValueDto(val key: String = "", val value: JsonElement? = null)

@Serializable
data class KvPutDto(val value: JsonElement?)

@Serializable
data class BriefSlotDto(
    val id: String,
    val time: String,
    val enabled: Boolean = true,
    val days: List<Int> = listOf(0, 1, 2, 3, 4, 5, 6),
)

@Serializable
data class BriefSettingsDto(val briefs: List<BriefSlotDto>)

@Serializable
data class RunBriefRequest(val kind: String = "extra1")

@Serializable
data class RunBriefResponse(@SerialName("brief_id") val briefId: String)
