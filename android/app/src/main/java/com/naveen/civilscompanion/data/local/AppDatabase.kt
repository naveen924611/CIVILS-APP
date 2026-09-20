package com.naveen.civilscompanion.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        NewsItemEntity::class,
        BriefEntity::class,
        RecordEntity::class,
        AlertEntity::class,
        ItemProgressEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun newsItems(): NewsItemDao
    abstract fun briefs(): BriefDao
    abstract fun records(): RecordDao
    abstract fun alerts(): AlertDao
    abstract fun progress(): ProgressDao
}

/** Bump when an entity changes. The database is rebuilt from the server (Prefs.dbSchema resets the sync cursor). */
const val DB_SCHEMA_VERSION = 2
