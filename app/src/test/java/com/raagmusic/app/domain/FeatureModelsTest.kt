package com.raagmusic.app.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FeatureModelsTest {
    private val day = LocalDate.of(2026, 10, 2)
    private val a = Track("abcdefghijk", "First", "Artist A", "", "abcdefghijk", Source.YOUTUBE)
    private val b = a.copy(id = "zyxwvutsrqp", title = "Second", artist = "Artist B", uri = "zyxwvutsrqp")
    @Test fun queueClampsInvalidIndexesPositionsAndRepeatModes() {
        val q = QueueSnapshot(listOf(a), 10, -99, true, 12).sanitized()
        assertEquals(0, q.index); assertEquals(0L, q.positionMs); assertEquals(0, q.repeat); assertTrue(q.shuffle)
        assertEquals(0, QueueSnapshot(index = -3).sanitized().index)
    }
    @Test fun listeningAccumulatesPerSongAndDay() {
        val s = FeatureState().record(a.id, day, 1000, plays = 1).record(a.id, day, 2000, skips = 1)
        assertEquals(listOf(ListenRecord(a.id, day.toString(), 3000, 1, 1)), s.history)
        assertEquals(2, s.record(a.id, day.plusDays(1), 500).history.size)
    }
    @Test fun privacyPreferencePreventsAnyNewListeningRecord() {
        val s = FeatureState(preferences = ListeningPreferences(analyticsEnabled = false))
        assertEquals(s, s.record(a.id, day, 1000, 1, 1))
    }
    @Test fun allTimeHistoryRemainsAndNegativeCountersCannotSubtract() {
        val s = FeatureState().record(a.id, day.minusDays(366), 1000).record(b.id, day, -50, -3, -2)
        assertEquals(listOf(ListenRecord(a.id, day.minusDays(366).toString(), 1000), ListenRecord(b.id, day.toString())), s.history)
    }
    @Test fun dailyMixExcludesFeedbackAndDeduplicates() {
        val library = LibraryState(savedTracks = listOf(a, b))
        val mix = dailyMix(listOf(a, b, a), library, FeatureState(lessLikeIds = setOf(a.id)), day)
        assertEquals(listOf(b), mix)
    }
    @Test fun likesAndListeningOutweighRandomVariety() {
        val library = LibraryState(favorites = setOf(a.id), savedTracks = listOf(a, b))
        assertEquals(a, dailyMix(listOf(b, a), library, FeatureState(), day).first())
        val state = FeatureState().record(a.id, day, 20 * 60_000)
        assertEquals(a, dailyMix(listOf(b, a), library.copy(favorites = emptySet()), state, day).first())
    }
    @Test fun earlySkipsLowerAffinityAndDayIsDeterministic() {
        val library = LibraryState(savedTracks = listOf(a, b))
        val state = FeatureState().record(a.id, day, skips = 10)
        assertEquals(b, dailyMix(listOf(a, b), library, state, day).first())
        assertEquals(dailyMix(listOf(a, b), library, state, day), dailyMix(listOf(a, b), library, state, day))
    }
}
