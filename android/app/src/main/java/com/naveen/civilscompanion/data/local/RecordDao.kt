package com.naveen.civilscompanion.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Upsert suspend fun upsert(rows: List<RecordEntity>)

    @Upsert suspend fun upsertOne(row: RecordEntity)

    @Query("SELECT * FROM records WHERE tbl = :tbl AND id = :id")
    suspend fun get(tbl: String, id: String): RecordEntity?

    @Query("SELECT * FROM records WHERE tbl = :tbl AND id = :id AND deleted = 0")
    fun observeOne(tbl: String, id: String): Flow<RecordEntity?>

    // order: 0 none, 1 n1 ascending, 2 n1 descending, 3 newest change first, 4 k1 A-Z, 5 text A-Z, 6 oldest change first
    @Query(
        "SELECT * FROM records WHERE tbl = :tbl AND deleted = 0 " +
            "AND (:k1 IS NULL OR k1 = :k1) AND (:k2 IS NULL OR k2 = :k2) " +
            "AND (:n1Min IS NULL OR n1 >= :n1Min) AND (:n1Max IS NULL OR n1 <= :n1Max) " +
            "AND (:like IS NULL OR text LIKE :like ESCAPE '\\') " +
            "ORDER BY CASE WHEN :order = 1 THEN n1 END ASC, CASE WHEN :order = 2 THEN n1 END DESC, " +
            "CASE WHEN :order = 3 THEN updatedAt END DESC, CASE WHEN :order = 4 THEN k1 END ASC, " +
            "CASE WHEN :order = 5 THEN text END ASC, CASE WHEN :order = 6 THEN updatedAt END ASC " +
            "LIMIT :limit",
    )
    fun observe(
        tbl: String, k1: String?, k2: String?, n1Min: Double?, n1Max: Double?, like: String?, order: Int, limit: Int,
    ): Flow<List<RecordEntity>>

    @Query(
        "SELECT * FROM records WHERE tbl = :tbl AND deleted = 0 " +
            "AND (:k1 IS NULL OR k1 = :k1) AND (:k2 IS NULL OR k2 = :k2) " +
            "AND (:n1Min IS NULL OR n1 >= :n1Min) AND (:n1Max IS NULL OR n1 <= :n1Max) " +
            "AND (:like IS NULL OR text LIKE :like ESCAPE '\\') " +
            "ORDER BY CASE WHEN :order = 1 THEN n1 END ASC, CASE WHEN :order = 2 THEN n1 END DESC, " +
            "CASE WHEN :order = 3 THEN updatedAt END DESC, CASE WHEN :order = 4 THEN k1 END ASC, " +
            "CASE WHEN :order = 5 THEN text END ASC, CASE WHEN :order = 6 THEN updatedAt END ASC " +
            "LIMIT :limit",
    )
    suspend fun query(
        tbl: String, k1: String?, k2: String?, n1Min: Double?, n1Max: Double?, like: String?, order: Int, limit: Int,
    ): List<RecordEntity>

    @Query(
        "SELECT COUNT(*) FROM records WHERE tbl = :tbl AND deleted = 0 " +
            "AND (:k1 IS NULL OR k1 = :k1) AND (:k2 IS NULL OR k2 = :k2) " +
            "AND (:n1Min IS NULL OR n1 >= :n1Min) AND (:n1Max IS NULL OR n1 <= :n1Max)",
    )
    fun observeCount(tbl: String, k1: String?, k2: String?, n1Min: Double?, n1Max: Double?): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM records WHERE tbl = :tbl AND deleted = 0 " +
            "AND (:k1 IS NULL OR k1 = :k1) AND (:k2 IS NULL OR k2 = :k2) " +
            "AND (:n1Min IS NULL OR n1 >= :n1Min) AND (:n1Max IS NULL OR n1 <= :n1Max)",
    )
    suspend fun count(tbl: String, k1: String?, k2: String?, n1Min: Double?, n1Max: Double?): Int

    @Query("SELECT * FROM records WHERE dirty = 1 ORDER BY updatedAt LIMIT :limit")
    suspend fun dirtyRows(limit: Int): List<RecordEntity>

    @Query("SELECT COUNT(*) FROM records WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    /** Only clears the flag when the row was not edited again while the push was in flight. */
    @Query("UPDATE records SET dirty = 0 WHERE tbl = :tbl AND id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(tbl: String, id: String, updatedAt: String)

    @Query("DELETE FROM records WHERE tbl = :tbl AND id = :id AND deleted = 1 AND updatedAt = :updatedAt")
    suspend fun purgeDeleted(tbl: String, id: String, updatedAt: String)

    @Query("DELETE FROM records WHERE tbl = :tbl AND id IN (:ids)")
    suspend fun delete(tbl: String, ids: List<String>)

    @Query("SELECT k2 AS sourceId, COUNT(*) AS n FROM records WHERE tbl = :tbl AND deleted = 0 AND k2 IS NOT NULL GROUP BY k2")
    fun observeK2Counts(tbl: String): Flow<List<SourceCount>>

    @Query("DELETE FROM records")
    suspend fun clearAll()
}
