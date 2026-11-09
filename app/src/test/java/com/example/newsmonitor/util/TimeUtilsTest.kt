package com.example.newsmonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun utcWithZSuffix() {
        assertEquals("2024-05-01 22:30:00", TimeUtils.parseToBeijing("2024-05-01T14:30:00Z"))
    }

    @Test
    fun utcWithMillisecondsAndZSuffix() {
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("2024-05-01T14:30:00.000Z")
        )
    }

    @Test
    fun explicitPlusEightOffset() {
        assertEquals(
            "2024-05-01 14:30:00",
            TimeUtils.parseToBeijing("2024-05-01T14:30:00+08:00")
        )
    }

    @Test
    fun explicitMinusFiveOffset() {
        assertEquals(
            "2024-05-02 03:30:00",
            TimeUtils.parseToBeijing("2024-05-01T14:30:00-05:00")
        )
    }

    @Test
    fun compactOffset() {
        assertEquals(
            "2024-05-01 14:30:00",
            TimeUtils.parseToBeijing("2024-05-01T14:30:00+0800")
        )
    }

    @Test
    fun noTimezoneMeansUtc() {
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("2024-05-01 14:30:00")
        )
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("2024-05-01T14:30:00")
        )
    }

    @Test
    fun dateOnlyMeansUtcMidnight() {
        assertEquals("2024-05-01 08:00:00", TimeUtils.parseToBeijing("2024-05-01"))
        assertEquals("2024-05-01 08:00:00", TimeUtils.parseToBeijing("2024/05/01"))
    }

    @Test
    fun englishMonthNames() {
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("01 May 2024 14:30:00")
        )
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("May 01, 2024 14:30:00")
        )
        assertEquals("2024-05-01 08:00:00", TimeUtils.parseToBeijing("01-May-2024"))
        assertEquals("2024-05-01 08:00:00", TimeUtils.parseToBeijing("May 01, 2024"))
    }

    @Test
    fun prefixesAreRemoved() {
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("Published 2024-05-01 14:30:00")
        )
        assertEquals("2024-05-01 08:00:00", TimeUtils.parseToBeijing("发布于 2024-05-01"))
    }

    @Test
    fun slashFormatAndNumericFallback() {
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("2024/05/01 14:30:00")
        )
        assertEquals(
            "2024-05-01 22:30:00",
            TimeUtils.parseToBeijing("2024-5-1 14:30")
        )
    }

    @Test
    fun invalidFallsBackToNow() {
        val result = TimeUtils.parseToBeijing("not a date at all")
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(result))
    }
}
