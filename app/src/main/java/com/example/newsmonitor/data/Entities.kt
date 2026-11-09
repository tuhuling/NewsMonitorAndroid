package com.example.newsmonitor.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "websites",
    indices = [Index(value = ["url"], unique = true)]
)
data class Website(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val remark: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: String = ""
)

@Entity(
    tableName = "keywords",
    indices = [Index(value = ["keyword"], unique = true)]
)
data class Keyword(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    @ColumnInfo(name = "created_at") val createdAt: String = ""
)

@Entity(
    tableName = "news",
    indices = [
        Index("news_url"),
        Index("website_name"),
        Index("publish_time"),
        Index("created_at")
    ]
)
data class News(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "website_id") val websiteId: Long? = null,
    @ColumnInfo(name = "website_name") val websiteName: String? = null,
    val title: String,
    @ColumnInfo(name = "title_zh") val titleZh: String? = null,
    val content: String? = null,
    @ColumnInfo(name = "content_zh") val contentZh: String? = null,
    @ColumnInfo(name = "news_url") val newsUrl: String? = null,
    @ColumnInfo(name = "publish_time") val publishTime: String? = null,
    @ColumnInfo(name = "match_keyword") val matchKeyword: String? = null,
    @ColumnInfo(name = "is_pushed") val isPushed: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L
)

@Entity(
    tableName = "logs",
    indices = [Index("created_at")]
)
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val level: String,
    val message: String,
    @ColumnInfo(name = "created_at") val createdAt: String = ""
)

@Entity(tableName = "config")
data class ConfigEntry(
    @PrimaryKey val k: String,
    val v: String? = null
)
