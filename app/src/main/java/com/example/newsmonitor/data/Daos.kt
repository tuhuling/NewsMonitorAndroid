package com.example.newsmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WebsiteDao {

    @Query("SELECT * FROM websites ORDER BY id DESC")
    fun observeAll(): Flow<List<Website>>

    @Query("SELECT * FROM websites ORDER BY id DESC")
    suspend fun getAll(): List<Website>

    @Query("SELECT * FROM websites WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): Website?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(website: Website): Long

    @Update
    suspend fun update(website: Website)

    @Query("DELETE FROM websites WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM websites WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM websites")
    suspend fun count(): Int
}

@Dao
interface KeywordDao {

    @Query("SELECT * FROM keywords ORDER BY id DESC")
    fun observeAll(): Flow<List<Keyword>>

    @Query("SELECT * FROM keywords ORDER BY id DESC")
    suspend fun getAll(): List<Keyword>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(keyword: Keyword): Long

    @Update
    suspend fun update(keyword: Keyword)

    @Query("DELETE FROM keywords WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM keywords WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM keywords")
    suspend fun count(): Int
}

@Dao
interface NewsDao {

    @Query(
        """
        SELECT * FROM news
        WHERE (:fromTime IS NULL OR (publish_time IS NOT NULL AND publish_time >= :fromTime))
          AND (:siteName IS NULL OR website_name = :siteName)
          AND (:keyword IS NULL OR instr(match_keyword, :keyword) > 0)
        ORDER BY publish_time DESC, id DESC
        """
    )
    fun observeFiltered(fromTime: String?, siteName: String?, keyword: String?): Flow<List<News>>

    @Query("SELECT * FROM news WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<News?>

    @Query("SELECT * FROM news WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): News?

    @Query("SELECT * FROM news WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<News>

    @Query("SELECT COUNT(*) FROM news WHERE news_url = :url AND created_at >= :since")
    suspend fun countRecentByUrl(url: String, since: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(news: News): Long

    @Query("DELETE FROM news WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE news SET is_pushed = 1 WHERE id = :id")
    suspend fun markPushed(id: Long)
}

@Dao
interface LogDao {

    @Insert
    suspend fun insert(log: LogEntry): Long

    @Query("SELECT * FROM logs ORDER BY id DESC")
    fun observeAll(): Flow<List<LogEntry>>

    @Query("SELECT * FROM logs ORDER BY id DESC")
    suspend fun getAll(): List<LogEntry>

    @Query("DELETE FROM logs")
    suspend fun clear()

    @Query("DELETE FROM logs WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: String)

    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT :maxRows)")
    suspend fun trimTo(maxRows: Int)
}

@Dao
interface ConfigDao {

    @Query("SELECT v FROM config WHERE k = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: ConfigEntry)

    @Query("SELECT * FROM config")
    suspend fun getAll(): List<ConfigEntry>
}
