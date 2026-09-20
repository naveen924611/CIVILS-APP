package com.naveen.civilscompanion.data

import com.naveen.civilscompanion.data.local.AlertEntity
import com.naveen.civilscompanion.data.local.BriefEntity
import com.naveen.civilscompanion.data.local.NewsItemEntity
import com.naveen.civilscompanion.data.remote.dto.AlertDto
import com.naveen.civilscompanion.data.remote.dto.BriefDto
import com.naveen.civilscompanion.data.remote.dto.FactDto
import com.naveen.civilscompanion.data.remote.dto.McqDto
import com.naveen.civilscompanion.data.remote.dto.NewsItemDto
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Server timestamps look like 2026-09-20T05:30:00.123456Z. Returns epoch milliseconds. */
fun parseInstant(text: String?): Long? =
    text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private val stringListSerializer = ListSerializer(String.serializer())
private val factListSerializer = ListSerializer(FactDto.serializer())
private val mcqListSerializer = ListSerializer(McqDto.serializer())

fun NewsItemDto.toEntity(json: Json) = NewsItemEntity(
    id = id, url = url, source = source, title = title, summary = summary,
    relevanceUpsc = relevanceUpsc, relevanceAppsc = relevanceAppsc,
    papersJson = json.encodeToString(stringListSerializer, papers),
    prelimsFactsJson = json.encodeToString(factListSerializer, prelimsFacts),
    mainsAngle = mainsAngle,
    keywordsJson = json.encodeToString(stringListSerializer, keywords),
    isApSpecific = isApSpecific,
    mcqsJson = json.encodeToString(mcqListSerializer, mcqs),
    publishedAt = parseInstant(publishedAt),
    audioUrl = audioUrl, audioSeconds = audioSeconds, briefId = briefId, updatedAt = updatedAt,
)

fun BriefDto.toEntity(json: Json) = BriefEntity(
    id = id, kind = kind,
    scheduledFor = parseInstant(scheduledFor) ?: 0L,
    status = status,
    itemIdsJson = json.encodeToString(stringListSerializer, itemIds),
    audioSecondsTotal = audioSecondsTotal, note = note,
    createdAt = parseInstant(createdAt) ?: 0L,
    updatedAt = updatedAt,
)

fun AlertDto.toEntity(json: Json) = AlertEntity(
    id = id, kind = kind, title = title, body = body,
    payloadJson = json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload),
    read = read, createdAt = parseInstant(createdAt) ?: 0L, updatedAt = updatedAt,
)

fun decodeStrings(json: Json, text: String): List<String> =
    runCatching { json.decodeFromString(stringListSerializer, text) }.getOrDefault(emptyList())

fun decodeFacts(json: Json, text: String): List<FactDto> =
    runCatching { json.decodeFromString(factListSerializer, text) }.getOrDefault(emptyList())

/** Reads one text value out of an alert's payload (for example the brief id). */
fun payloadValue(json: Json, payloadJson: String, key: String): String? =
    runCatching {
        json.parseToJsonElement(payloadJson).let { (it as kotlinx.serialization.json.JsonObject)[key] }
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
    }.getOrNull()
