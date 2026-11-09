package com.example.newsmonitor.ui.common

import android.content.Context
import android.os.Environment
import com.example.newsmonitor.data.LogEntry
import com.example.newsmonitor.data.News
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExportUtils {

    fun exportNewsCsv(context: Context, newsList: List<News>): File {
        val file = File(exportDir(context), "news_export_${timestamp()}.csv")
        val builder = StringBuilder()
        builder.append('﻿')
        builder.append("发布时间,新闻源,标题,匹配关键字,链接\n")

        newsList
            .sortedByDescending { it.publishTime ?: "" }
            .forEach { news ->
                val displayTitle = news.titleZh?.takeIf { it.isNotBlank() } ?: news.title
                builder.append(csvEscape(news.publishTime ?: "")).append(',')
                    .append(csvEscape(news.websiteName ?: "")).append(',')
                    .append(csvEscape(displayTitle)).append(',')
                    .append(csvEscape(news.matchKeyword ?: "")).append(',')
                    .append(csvEscape(news.newsUrl ?: "")).append('\n')
            }

        file.writeText(builder.toString(), Charsets.UTF_8)
        return file
    }

    fun exportLogsTxt(context: Context, logs: List<LogEntry>): File {
        val file = File(exportDir(context), "logs_${timestamp()}.txt")
        val builder = StringBuilder()
        logs.sortedBy { it.id }.forEach { log ->
            builder.append('[').append(log.createdAt).append("] ")
                .append(log.level).append(' ')
                .append(log.message).append('\n')
        }
        file.writeText(builder.toString(), Charsets.UTF_8)
        return file
    }

    private fun exportDir(context: Context): File {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun csvEscape(value: String): String {
        val needsQuote = value.contains(',') || value.contains('"') ||
            value.contains('\n') || value.contains('\r')
        return if (needsQuote) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
