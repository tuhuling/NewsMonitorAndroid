package com.example.newsmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Website::class,
        Keyword::class,
        News::class,
        LogEntry::class,
        ConfigEntry::class,
        IntelTemplate::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun websiteDao(): WebsiteDao
    abstract fun keywordDao(): KeywordDao
    abstract fun newsDao(): NewsDao
    abstract fun logDao(): LogDao
    abstract fun configDao(): ConfigDao
    abstract fun intelTemplateDao(): IntelTemplateDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `news` ADD COLUMN `is_ai_generated` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `intel_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /** 将“AI生成情报”登记为关键词，并回填历史 AI 情报的关键词标签。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `news` SET `match_keyword` = 'AI生成情报' " +
                        "WHERE `is_ai_generated` = 1 " +
                        "AND (`match_keyword` IS NULL OR `match_keyword` = '')"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `keywords` (`keyword`, `created_at`) " +
                        "VALUES ('AI生成情报', '')"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "news_monitor.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
