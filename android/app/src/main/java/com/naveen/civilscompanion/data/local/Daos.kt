package com.naveen.civilscompanion.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsItemDao {
    @Upsert suspend fun upsert(items: List<NewsItemEntity>)

    @Query("DELETE FROM news_items WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT * FROM news_items WHERE id IN (:ids)")
    fun observe(ids: List<String>): Flow<List<NewsItemEntity>>

    @Query("SELECT * FROM news_items WHERE id IN (:ids)")
    suspend fun get(ids: List<String>): List<NewsItemEntity>
}

@Dao
interface BriefDao {
    @Upsert suspend fun upsert(items: List<BriefEntity>)

    @Query("DELETE FROM briefs WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT * FROM briefs ORDER BY scheduledFor DESC")
    fun observeAll(): Flow<List<BriefEntity>>

    @Query("SELECT * FROM briefs WHERE id = :id")
    suspend fun get(id: String): BriefEntity?

    @Query("SELECT * FROM briefs WHERE status = 'ready' AND scheduledFor >= :since ORDER BY scheduledFor DESC")
    suspend fun readySince(since: Long): List<BriefEntity>
}

@Dao
interface CardDao {
    @Upsert suspend fun upsert(items: List<CardEntity>)

    @Query("DELETE FROM cards WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT sourceId AS sourceId, COUNT(*) AS n FROM cards WHERE sourceId IS NOT NULL GROUP BY sourceId")
    fun observeSourceCounts(): Flow<List<SourceCount>>
}

@Dao
interface AlertDao {
    @Upsert suspend fun upsert(items: List<AlertEntity>)

    @Query("DELETE FROM alerts WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT * FROM alerts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE read = 0")
    fun observeUnread(): Flow<Int>

    @Query("UPDATE alerts SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE alerts SET read = 1")
    suspend fun markAllRead()
}

@Dao
interface ProgressDao {
    @Upsert suspend fun upsert(item: ItemProgressEntity)

    @Query("SELECT itemId FROM item_progress WHERE heard = 1")
    fun observeHeard(): Flow<List<String>>
}
