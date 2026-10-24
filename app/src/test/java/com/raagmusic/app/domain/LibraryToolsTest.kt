package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LibraryToolsTest {
    private val today = LocalDate.of(2026, 10, 2)
    private val a = Track("a", "Zulu", "Alpha", "", "https://example.org/a")
    private val b = Track("b", "Apple", "Zulu", "", "content://music/b")
    private val c = Track("c", "Berry", "Beta", "", "https://example.org/c")
    private val library = LibraryState(favorites = setOf(a.id, c.id), savedTracks = listOf(a,b,c), likedAt = mapOf(a.id to 100, c.id to 200))
    @Test fun recentSortingUsesStableInsertionOrderAndTitleIgnoresCase() {
        assertEquals(listOf(c,b,a), libraryTracks(library, LibrarySort.RECENT, LibraryFilter.ALL))
        assertEquals(listOf(b,c,a), libraryTracks(library, LibrarySort.TITLE, LibraryFilter.ALL))
        assertEquals(listOf(a,c,b), libraryTracks(library, LibrarySort.ARTIST, LibraryFilter.ALL))
    }
    @Test fun filtersApplyWithSearchAndOnlyCompletedDownloadIds() {
        assertEquals(listOf(b), libraryTracks(library, LibrarySort.RECENT, LibraryFilter.LOCAL))
        assertEquals(listOf(c,b), libraryTracks(library, LibrarySort.RECENT, LibraryFilter.LOCAL, downloaded = setOf(c.id)))
        assertEquals(listOf(a), libraryTracks(library, LibrarySort.RECENT, LibraryFilter.FAVORITES, "alpha"))
    }
    @Test fun recentlyLikedUsesFavoriteDatesAndExcludesFeedback() {
        assertEquals(listOf(c,a), smartPlaylist(SmartPlaylist.RECENT_LIKES, library, FeatureState(), today))
        assertEquals(listOf(a), smartPlaylist(SmartPlaylist.RECENT_LIKES, library, FeatureState(lessLikeIds = setOf(c.id)), today))
    }
    @Test fun mostPlayedUsesCountsThenTimeAndRediscoverRequiresOldListening() {
        val features = FeatureState().record(a.id, today, 1000, 2).record(b.id, today.minusDays(40), 2000, 2)
        assertEquals(listOf(b,a), smartPlaylist(SmartPlaylist.MOST_PLAYED, library, features, today))
        assertEquals(listOf(c,b), smartPlaylist(SmartPlaylist.REDISCOVER, library, features, today))
    }
    @Test fun timelineCombinesDailyCountsWithoutInventingExactTimes() {
        val features = FeatureState().record(a.id, today, 500, 1).record(a.id, today, 600, 2)
            .record(b.id, today.minusDays(1), 2000, 1).record("missing", today, 100, 1)
        val days = historyTimeline(library, features)
        assertEquals(listOf(today.toString(), today.minusDays(1).toString()), days.map { it.day })
        assertEquals(listOf(a), days.first().tracks); assertEquals(4, days.first().plays); assertEquals(1200L, days.first().milliseconds)
    }
    @Test fun presetsAndCustomGainsStayBounded() {
        assertEquals(listOf(12,-12,0,0,0), EqualizerSettings(preset = EqualizerPreset.CUSTOM, customGains = listOf(90,-90)).gains)
        assertEquals(listOf(6,4,0,-1,0), EqualizerSettings(preset = EqualizerPreset.BASS).gains)
    }
}
