package com.raagmusic.app.updates

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UpdateWindowsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun time(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    @Test fun morningAndEveningStartSeparateWindows() {
        val before = time("2026-10-25T07:59:00+05:30")
        val morning = time("2026-10-25T08:00:00+05:30")
        val evening = time("2026-10-25T19:00:00+05:30")
        assertFalse(UpdateWindows.sameWindow(before, morning, zone))
        assertTrue(UpdateWindows.sameWindow(morning, time("2026-10-25T18:59:00+05:30"), zone))
        assertFalse(UpdateWindows.sameWindow(morning, evening, zone))
        assertTrue(UpdateWindows.sameWindow(evening, time("2026-10-26T07:59:00+05:30"), zone))
        assertFalse(UpdateWindows.sameWindow(evening, time("2026-10-26T08:00:00+05:30"), zone))
    }
    @Test fun nextWindowIsFutureAndRespectsLocalClock() {
        val now = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, zone)
        assertEquals(now.toLocalDate().plusDays(1), UpdateWindows.nextStart(now, true).toLocalDate())
        assertEquals(8, UpdateWindows.nextStart(now, true).hour)
        assertEquals(now.toLocalDate(), UpdateWindows.nextStart(now, false).toLocalDate())
        assertEquals(19, UpdateWindows.nextStart(now, false).hour)
    }
    @Test fun dstChangesKeepMorningAtEightInsteadOfAddingTwentyFourHours() {
        val now = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, ZoneId.of("America/New_York"))
        val next = UpdateWindows.nextStart(now, true)
        assertEquals(8, next.hour)
        assertEquals(-4 * 3600, next.offset.totalSeconds)
    }
    @Test fun clockRollbackDoesNotTreatAFutureCheckAsFresh() {
        assertFalse(UpdateWindows.sameWindow(time("2026-10-25T09:00:00+05:30"), time("2026-10-25T08:00:00+05:30"), zone))
    }
}
