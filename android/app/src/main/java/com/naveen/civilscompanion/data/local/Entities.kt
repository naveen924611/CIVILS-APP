package com.naveen.civilscompanion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Lists (papers, facts, keywords, questions) are stored as JSON text; the screens decode them. */
@Entity(tableName = "news_items")
data class NewsItemEntity(
    @PrimaryKey val id: String,
    val url: String,
    val source: String,
    val title: String,
    val summary: String,
    val relevanceUpsc: Int,
    val relevanceAppsc: Int,
    val papersJson: String,
    val prelimsFactsJson: String,
    val mainsAngle: String,
    val keywordsJson: String,
    val isApSpecific: Boolean,
    val mcqsJson: String,
    val publishedAt: Long?,
    val audioUrl: String?,
    val audioSeconds: Int?,
    val briefId: String?,
    val updatedAt: String,
)

@Entity(tableName = "briefs")
data class BriefEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val scheduledFor: Long,
    val status: String,
    val itemIdsJson: String,
    val audioSecondsTotal: Int,
    val note: String,
    val createdAt: Long,
    val updatedAt: String,
)

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String,
    val front: String,
    val back: String,
    val groupName: String,
    val sourceId: String?,
    val dueAt: Long?,
    val updatedAt: String,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val payloadJson: String,
    val read: Boolean,
    val createdAt: Long,
    val updatedAt: String,
)

/** Things only this tablet knows (server never overwrites them). */
@Entity(tableName = "item_progress")
data class ItemProgressEntity(
    @PrimaryKey val itemId: String,
    val heard: Boolean,
)

data class SourceCount(val sourceId: String, val n: Int)
