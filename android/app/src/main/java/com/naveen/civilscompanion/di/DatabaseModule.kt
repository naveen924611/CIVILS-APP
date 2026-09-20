package com.naveen.civilscompanion.di

import android.content.Context
import androidx.room.Room
import com.naveen.civilscompanion.data.local.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "civils.db").build()

    @Provides fun newsItems(db: AppDatabase) = db.newsItems()
    @Provides fun briefs(db: AppDatabase) = db.briefs()
    @Provides fun cards(db: AppDatabase) = db.cards()
    @Provides fun alerts(db: AppDatabase) = db.alerts()
    @Provides fun progress(db: AppDatabase) = db.progress()
}
