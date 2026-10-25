package com.raagmusic.app.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class QuickPicksFeedbackTest {
    private val day = LocalDate.of(2026, 10, 25)
    private fun song(id: String, artist: String = id) = Track(id, id, artist, "", id, durationSeconds = 180)
    @Test fun completionsAndLikesPromoteRelevantArtistsOverGenericProviderOrder() {
        val heard = song("heard", "Loved artist")
        val match = song("new", heard.artist)
        val unrelated = (1..20).map { song("generic-$it") }
        val saved = LibraryState(savedTracks = listOf(heard), recentIds = listOf(heard.id))
        val feedback = FeatureState(history = listOf(ListenRecord(heard.id, day.toString(), 150_000, 1, completions = 1)))
        val picks = rankQuickPicks(unrelated + match, saved, feedback, day)
        assertEquals(match, picks.first())
        assertEquals(match, rankQuickPicks(unrelated + match, saved.copy(favorites = setOf(heard.id)), FeatureState(), day).first())
    }
    @Test fun repeatedEarlySkipsDoNotBecomePositiveRetrievalSeeds() {
        val skipped = song("skipped", "Skipped artist")
        val feedback = FeatureState(history = listOf(ListenRecord(skipped.id, day.toString(), 60_000, 3, 3)))
        val library = LibraryState(savedTracks = listOf(skipped), recentIds = listOf(skipped.id))
        assertTrue(quickPickSeeds(library, feedback).isEmpty())
        assertTrue(preferredArtists(library, feedback, day).isEmpty())
        assertTrue(positiveRecommendationTracks(library, feedback).isEmpty())
    }
    @Test fun familiarSongsLeaveRoomForSupportedDiscoveryAndBlockedAliasesStayOut() {
        val familiar = (1..10).map { song("familiar-$it", "Artist $it") }
        val fresh = familiar.mapIndexed { index, song -> song("fresh-$index", song.artist) }
        val library = LibraryState(savedTracks = familiar, favorites = familiar.map { it.id }.toSet())
        val picks = rankQuickPicks(familiar + fresh, library, FeatureState(), day, limit = 10)
        assertTrue(picks.count { it.id.startsWith("fresh") } >= 6)
        val original = song("blocked", "Artist").copy(title = "My song (Official Audio)")
        val alias = original.copy(id = "alias", title = "My song (Official Music Video)")
        assertTrue(rankQuickPicks(listOf(alias), LibraryState(savedTracks = listOf(original)), FeatureState(lessLikeIds = setOf(original.id)), day).isEmpty())
    }
    @Test fun meaningfulFeedbackInvalidatesTasteButMetadataAndTinyTimeTicksDoNot() {
        val library = LibraryState(savedTracks = listOf(song("heard")))
        val state = FeatureState(history = listOf(ListenRecord("heard", day.toString(), 40_000, 1)))
        assertEquals(recommendationTasteKey(library, state), recommendationTasteKey(library, state.copy(history = state.history.map { it.copy(milliseconds = 45_000) })))
        assertNotEquals(recommendationTasteKey(library, state), recommendationTasteKey(library, state.copy(history = state.history.map { it.copy(completions = 1) })))
        val disabled = state.copy(preferences = state.preferences.copy(analyticsEnabled = false))
        assertEquals(disabled, disabled.record("another", day, 180_000, 1, 1, 1))
    }
}
