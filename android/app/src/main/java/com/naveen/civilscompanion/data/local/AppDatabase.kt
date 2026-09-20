package com.naveen.civilscompanion.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        NewsItemEntity::class,
        BriefEntity::class,
        CardEntity::class,
        AlertEntity::class,
        ItemProgressEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun newsItems(): NewsItemDao
    abstract fun briefs(): BriefDao
    abstract fun cards(): CardDao
    abstract fun alerts(): AlertDao
    abstract fun progress(): ProgressDao
}
