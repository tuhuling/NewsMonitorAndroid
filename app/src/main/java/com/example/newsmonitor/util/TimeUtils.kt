package com.example.newsmonitor.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

/**
 * 时间转换规则（需求红线）：
 * - 带 Z 或 +HH:MM / -HH:MM 偏移的，按显式时区解析；
 * - 无时区信息一律视为 UTC；
 * - 最终统一转换为东八区北京时间 yyyy-MM-dd HH:mm:ss；
 * - 解析失败使用当前北京时间。
 */
object TimeUtils {

    private const val OUTPUT_PATTERN = "yyyy-MM-dd HH:mm:ss"

    private val BEIJING: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private val ZONED_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'"
    )

    private val UNZONED_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "dd MMMM yyyy HH:mm:ss",
        "MMMM dd, yyyy HH:mm:ss",
        "dd MMM yyyy HH:mm:ss",
        "MMM dd, yyyy HH:mm:ss",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "dd-MMM-yyyy",
        "MMM dd, yyyy",
        "MMMM dd, yyyy",
        "dd MMMM yyyy",
        "dd MMM yyyy"
    )

    private val NUMBER_FALLBACK = Pattern.compile(
        "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[\\sT](\\d{1,2}):(\\d{2})(?::(\\d{2}))?"
    )
    private val ZONE_SUFFIX = Pattern.compile("(?:Z|[+-]\\d{2}:?\\d{2})$", Pattern.CASE_INSENSITIVE)

    fun nowBeijingString(): String = formatBeijing(Date())

    fun formatBeijing(date: Date): String {
        val format = SimpleDateFormat(OUTPUT_PATTERN, Locale.CHINA)
        format.timeZone = BEIJING
        return format.format(date)
    }

    fun parseToBeijing(raw: String?): String {
        if (raw.isNullOrBlank()) return nowBeijingString()

        var value = raw.trim()
            .replace("Published", "", ignoreCase = true)
            .replace("Last updated", "", ignoreCase = true)
            .replace("Updated", "", ignoreCase = true)
            .replace("发布于", "")
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .replace(" ", " ")
            .trim()

        if (value.isEmpty()) return nowBeijingString()

        var explicitZone = ZONE_SUFFIX.matcher(value).find()
        val lower = value.lowercase(Locale.ENGLISH)
        if (lower.endsWith(" utc")) {
            value = value.substring(0, value.length - 4).trim()
            explicitZone = true
        } else if (lower.endsWith(" gmt")) {
            value = value.substring(0, value.length - 4).trim()
            explicitZone = true
        }

        if (explicitZone) {
            for (pattern in ZONED_PATTERNS) {
                parse(value, pattern, UTC)?.let { return formatBeijing(it) }
            }
        }

        for (pattern in UNZONED_PATTERNS) {
            parse(value, pattern, UTC)?.let { return formatBeijing(it) }
        }

        val match = NUMBER_FALLBACK.matcher(value)
        if (match.find()) {
            try {
                val calendar = Calendar.getInstance(UTC)
                calendar.clear()
                calendar.set(
                    match.group(1)!!.toInt(),
                    match.group(2)!!.toInt() - 1,
                    match.group(3)!!.toInt(),
                    match.group(4)!!.toInt(),
                    match.group(5)!!.toInt(),
                    match.group(6)?.toIntOrNull() ?: 0
                )
                return formatBeijing(calendar.time)
            } catch (_: Exception) {
                // fall through to "now"
            }
        }

        return nowBeijingString()
    }

    private fun parse(input: String, pattern: String, defaultZone: TimeZone): Date? {
        return try {
            val format = SimpleDateFormat(pattern, Locale.ENGLISH)
            format.timeZone = defaultZone
            format.isLenient = false
            val position = ParsePosition(0)
            val date = format.parse(input, position)
            if (date != null && position.index == input.length) date else null
        } catch (_: Exception) {
            null
        }
    }

    fun startOfToday(): String {
        val calendar = Calendar.getInstance(BEIJING)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return formatBeijing(calendar.time)
    }

    fun cutoffDaysAgo(days: Int): String {
        val calendar = Calendar.getInstance(BEIJING)
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return formatBeijing(calendar.time)
    }

    fun cutoffMonthsAgo(months: Int): String {
        val calendar = Calendar.getInstance(BEIJING)
        calendar.add(Calendar.MONTH, -months)
        return formatBeijing(calendar.time)
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun logTime(createdAt: String): String {
        return if (createdAt.length >= 19) createdAt.substring(11, 19) else createdAt
    }
}
