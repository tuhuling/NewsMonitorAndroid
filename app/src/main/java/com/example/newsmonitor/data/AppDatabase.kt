package com.example.newsmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Website::class, Keyword::class, News::class, LogEntry::class, ConfigEntry::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun websiteDao(): WebsiteDao
    abstract fun keywordDao(): KeywordDao
    abstract fun newsDao(): NewsDao
    abstract fun logDao(): LogDao
    abstract fun configDao(): ConfigDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "news_monitor.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
