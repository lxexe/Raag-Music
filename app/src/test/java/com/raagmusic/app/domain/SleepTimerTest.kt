package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {
    @Test fun activeCountdownUsesElapsedTimeAndExpiresAtZero() {
        val timer = RunningSleepTimer(SleepTimerSpec(61_000), 1_000, 10_000)
        assertEquals(60_000L, timer.remainingMs(10_000))
        assertEquals(45_000L, timer.remainingMs(25_000))
        assertEquals(0L, timer.remainingMs(80_000))
        assertEquals(0L, RunningSleepTimer(SleepTimerSpec(500), 1000, 10).remainingMs(10))
    }
    @Test fun remainingTimeIsReadableAndNeverShowsZeroBeforeExpiry() {
        assertEquals("15:00", sleepCountdownLabel(900_000))
        assertEquals("14:59", sleepCountdownLabel(899_000))
        assertEquals("0:01", sleepCountdownLabel(1))
        assertEquals("0:00", sleepCountdownLabel(0))
        assertTrue(SleepTimerSpec(finishTrackId = "song").active)
        assertFalse(SleepTimerSpec().active)
    }
}
