package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ListeningAnalyticsTest {
    private val today = LocalDate.of(2026, 10, 20)
    private val tracks = listOf(Track("a", "First", "Artist", "", "a"), Track("b", "Second", "Other", "", "b"))
    private val rows = listOf(
        ListenRecord("a", today.toString(), 120_000, 2, 1),
        ListenRecord("b", today.minusDays(6).toString(), 60_000, 1),
        ListenRecord("a", today.minusDays(365).toString(), 180_000, 1),
        ListenRecord("a", "invalid", 900_000, 10),
        ListenRecord("a", today.plusDays(1).toString(), 900_000, 10),
    )
    @Test fun rangeBoundariesExcludeFutureAndMalformedDates() {
        val day = listeningAnalytics(rows, tracks, AnalyticsRange.DAY, today)
        assertEquals(120_000L, day.milliseconds); assertEquals(2, day.plays); assertEquals(50, day.skipPercent)
        val week = listeningAnalytics(rows, tracks, AnalyticsRange.WEEK, today)
        assertEquals(180_000L, week.milliseconds); assertEquals(2, week.songs); assertEquals(2, week.days)
        assertEquals(180_000L, week.activity.sumOf { it.milliseconds })
        assertEquals(180_000L, listeningAnalytics(rows, tracks, AnalyticsRange.YEAR, today).milliseconds)
        assertEquals(360_000L, listeningAnalytics(rows, tracks, AnalyticsRange.ALL, today).milliseconds)
    }
    @Test fun everyRangeAccountsForAllDaysAndKeepsRankingsAndChartBounded() {
        val history = (0..730).map { ListenRecord("a", today.minusDays(it.toLong()).toString(), 1000, 1) }
        AnalyticsRange.entries.forEach { range ->
            val result = listeningAnalytics(history, tracks, range, today)
            assertEquals(result.milliseconds, result.activity.sumOf { it.milliseconds })
            assertTrue(result.activity.size <= 7)
            assertEquals("First", result.topSongs.single().label)
        }
    }
    @Test fun disabledAnalyticsLeavesHistoryUntouchedWhileNewDefaultsAreEnabled() {
        assertTrue(ListeningPreferences().analyticsEnabled)
        val state = FeatureState(preferences = ListeningPreferences(analyticsEnabled = false), history = rows)
        assertEquals(state, state.record("a", today, 9999, 1, 1))
    }
}
