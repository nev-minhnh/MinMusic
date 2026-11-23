package com.metrolist.core.api.library

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryGroupsTest {
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val hanoi: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    private fun ms(
        zone: ZoneId,
        y: Int,
        m: Int,
        d: Int,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0,
        nano: Int = 0,
    ): Long = ZonedDateTime.of(y, m, d, hour, minute, second, nano, zone).toInstant().toEpochMilli()

    @Test
    fun labelsOfADay() {
        val now = ms(hanoi, 2026, 11, 23, 10)
        fun label(y: Int, m: Int, d: Int, hour: Int = 12) = HistoryGroups.labelOf(ms(hanoi, y, m, d, hour), now, hanoi)
        assertEquals(HistoryGroups.TODAY, label(2026, 11, 23, 0))
        assertEquals(HistoryGroups.TODAY, label(2026, 11, 23, 23))
        assertEquals(HistoryGroups.YESTERDAY, label(2026, 11, 22))
        assertEquals(HistoryGroups.WEEK, label(2026, 11, 21))
        assertEquals(HistoryGroups.WEEK, label(2026, 11, 17))
        assertEquals(HistoryGroups.MONTH, label(2026, 11, 16))
        assertEquals(HistoryGroups.MONTH, label(2026, 11, 1))
        assertEquals(HistoryGroups.OLDER, label(2026, 10, 31))
        assertEquals(HistoryGroups.OLDER, label(2025, 11, 23))
    }

    @Test
    fun aPlayFromTheFutureCountsAsToday() {
        val now = ms(hanoi, 2026, 11, 23, 10)
        assertEquals(HistoryGroups.TODAY, HistoryGroups.labelOf(ms(hanoi, 2026, 11, 25), now, hanoi))
    }

    @Test
    fun midnightEdgesFollowTheLocalZone() {
        val now = ms(hanoi, 2026, 11, 23, 0, 0, 0)
        assertEquals(HistoryGroups.YESTERDAY, HistoryGroups.labelOf(ms(hanoi, 2026, 11, 22, 23, 59, 59, 999_000_000), now, hanoi))
        assertEquals(HistoryGroups.TODAY, HistoryGroups.labelOf(now, now, hanoi))
        // the zone decides where the day ends: 23:00 in New York is already the next day in Ho Chi Minh City
        val play = ms(newYork, 2026, 11, 21, 23, 0)
        assertEquals(HistoryGroups.YESTERDAY, HistoryGroups.labelOf(play, ms(newYork, 2026, 11, 22, 8), newYork))
        assertEquals(HistoryGroups.TODAY, HistoryGroups.labelOf(play, ms(hanoi, 2026, 11, 22, 8), hanoi))
    }

    @Test
    fun daylightSavingDayIsNotShortenedOrDoubled() {
        // 2026-03-08 has 23 hours in New York (clocks jump at 02:00); the calendar day decides, not a 24 h window.
        val now = ms(newYork, 2026, 3, 8, 12)
        fun label(y: Int, m: Int, d: Int, hour: Int, minute: Int = 0) = HistoryGroups.labelOf(ms(newYork, y, m, d, hour, minute), now, newYork)
        assertEquals(HistoryGroups.TODAY, label(2026, 3, 8, 0, 30))
        assertEquals(HistoryGroups.YESTERDAY, label(2026, 3, 7, 23, 59))
        assertEquals(HistoryGroups.YESTERDAY, label(2026, 3, 7, 0, 0))
        assertEquals(HistoryGroups.WEEK, label(2026, 3, 6, 10))
        assertEquals(HistoryGroups.MONTH, label(2026, 3, 1, 10))
        assertEquals(HistoryGroups.OLDER, label(2026, 2, 28, 10))
        // the autumn change (clocks go back on 2026-11-01) behaves the same way
        val autumn = ms(newYork, 2026, 11, 1, 12)
        assertEquals(HistoryGroups.YESTERDAY, HistoryGroups.labelOf(ms(newYork, 2026, 10, 31, 23, 30), autumn, newYork))
        assertEquals(HistoryGroups.TODAY, HistoryGroups.labelOf(ms(newYork, 2026, 11, 1, 0, 30), autumn, newYork))
    }

    @Test
    fun groupsAreRunsOfEqualLabelsWithOffsets() {
        val now = ms(hanoi, 2026, 11, 23, 10)
        val newestFirst =
            listOf(
                ms(hanoi, 2026, 11, 23, 9),
                ms(hanoi, 2026, 11, 23, 8),
                ms(hanoi, 2026, 11, 22, 20),
                ms(hanoi, 2026, 11, 19, 20),
                ms(hanoi, 2026, 11, 3, 20),
                ms(hanoi, 2026, 9, 3, 20),
                ms(hanoi, 2026, 8, 3, 20),
            )
        val groups = HistoryGroups.compute(newestFirst, now, hanoi)
        assertEquals(
            listOf(
                HistoryGroups.Group(HistoryGroups.TODAY, 0),
                HistoryGroups.Group(HistoryGroups.YESTERDAY, 2),
                HistoryGroups.Group(HistoryGroups.WEEK, 3),
                HistoryGroups.Group(HistoryGroups.MONTH, 4),
                HistoryGroups.Group(HistoryGroups.OLDER, 5),
            ),
            groups,
        )
        // oldest first gives the same runs in the opposite order
        val ascending = HistoryGroups.compute(newestFirst.reversed(), now, hanoi)
        assertEquals(listOf(HistoryGroups.OLDER, HistoryGroups.MONTH, HistoryGroups.WEEK, HistoryGroups.YESTERDAY, HistoryGroups.TODAY), ascending.map { it.label })
        assertEquals(listOf(0, 2, 3, 4, 5), ascending.map { it.offset })
        assertEquals(emptyList(), HistoryGroups.compute(emptyList(), now, hanoi))
    }

    @Test
    fun periodStarts() {
        val now = 10_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertEquals(now - 7 * day, HistoryGroups.periodStart("week", now))
        assertEquals(now - 30 * day, HistoryGroups.periodStart("month", now))
        assertEquals(now - 365 * day, HistoryGroups.periodStart("year", now))
        assertEquals(0L, HistoryGroups.periodStart("all", now))
        assertNull(HistoryGroups.periodStart("decade", now))
    }
}
