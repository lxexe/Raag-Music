package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecommendationsTest {
    private val day = LocalDate.of(2026, 10, 3)
    private fun song(id: String, artist: String = id) = Track(id, id, artist, "", id, durationSeconds = 180)
    @Test fun uploadAliasesDoNotRepeatButDifferentVersionsRemainAvailable() {
        val audio = song("audio", "Singer").copy(title = "A Song (Official Audio)")
        val video = song("video", "Singer").copy(title = "A Song (Official Music Video)", durationSeconds = 183)
        val live = song("live", "Singer").copy(title = "A Song (Live)")
        val remix = song("remix", "Singer").copy(title = "A Song (Remix)")
        val result = rankRecommendations(listOf(audio, video, live, remix), null, LibraryState(), FeatureState(), day)
        assertEquals(3, result.size)
        assertEquals(1, result.count { it.id in setOf("audio", "video") })
        assertTrue(result.any { it.id == "live" }); assertTrue(result.any { it.id == "remix" })
        val queued = rankRecommendations(listOf(audio, video, live), audio, LibraryState(), FeatureState(), day)
        assertEquals(listOf("live"), queued.map { it.id })
        val disliked = rankRecommendations(listOf(video, live), null, LibraryState(savedTracks = listOf(audio)), FeatureState(lessLikeIds = setOf(audio.id)), day)
        assertEquals(listOf("live"), disliked.map { it.id })
    }
    @Test fun providerSimilarityAndSongMetadataBeatUnrelatedFavorites() {
        val seed = song("seed", "Artist").copy(album = "Album", genre = "Jazz", mood = "Romance")
        val match = song("match", "Artist").copy(album = "Album", genre = "Jazz", mood = "Romance")
        val favorite = song("favorite")
        val library = LibraryState(savedTracks = listOf(favorite), favorites = setOf(favorite.id))
        assertEquals(match, rankRecommendations(listOf(favorite, match), seed, library, FeatureState(), day, relatedIds = setOf(match.id)).first())
    }
    @Test fun blockedQueuedCurrentAndLongVideosAreExcluded() {
        val seed = song("seed")
        val candidates = listOf(seed, song("blocked"), song("queued"), song("video").copy(durationSeconds = 5000), song("valid"), song("valid"))
        val result = rankRecommendations(candidates, seed, LibraryState(), FeatureState(lessLikeIds = setOf("blocked")), day, excludedIds = setOf("queued"))
        assertEquals(listOf("valid"), result.map { it.id })
    }
    @Test fun listeningHistoryPromotesArtistAndEarlySkipsReduceAffinity() {
        val known = song("known", "Favorite artist")
        val likedArtist = song("discover", known.artist)
        val skipped = song("skipped")
        val history = FeatureState(history = listOf(ListenRecord(known.id, day.toString(), milliseconds = 1_800_000, plays = 6), ListenRecord(skipped.id, day.toString(), skips = 5)))
        val library = LibraryState(savedTracks = listOf(known, skipped))
        val result = rankRecommendations(listOf(skipped, song("neutral"), likedArtist), null, library, history, day)
        assertEquals(likedArtist, result.first())
        assertEquals(skipped, result.last())
    }
    @Test fun missingMetadataDoesNotCreateFalseAlbumGenreOrMoodMatches() {
        val seed = song("seed")
        val provider = song("provider")
        assertEquals(provider, rankRecommendations(listOf(song("other"), provider), seed, LibraryState(), FeatureState(), day, relatedIds = setOf(provider.id)).first())
    }
    @Test fun resultsAreStableBoundedAndDiverse() {
        val tracks = (0..29).map { song("$it", if (it < 15) "Same artist" else "Artist $it") }
        val first = rankRecommendations(tracks, null, LibraryState(), FeatureState(), day, limit = 20)
        assertEquals(20, first.size)
        assertEquals(first, rankRecommendations(tracks, null, LibraryState(), FeatureState(), day, limit = 20))
        assertTrue(first.take(4).map { it.artist }.distinct().size >= 3)
    }
    @Test fun timeOfDayProvidesRelevantTieBreaker() {
        val sleep = song("sleep").copy(mood = "Sleep")
        val sunny = song("sunny").copy(mood = "Feel good")
        assertEquals(sunny, rankRecommendations(listOf(sleep, sunny), null, LibraryState(), FeatureState(), day, hour = 8).first())
        assertEquals(sleep, rankRecommendations(listOf(sleep, sunny), null, LibraryState(), FeatureState(), day, hour = 23).first())
    }
    @Test fun rememberingSparseMetadataPreservesAlbumGenreMoodAndHydratesArtist() {
        val rich = song("track", "Old credit").copy(album = "Record", genre = "Jazz", mood = "Unwind")
        val sparse = song("track", "Verified credit")
        val saved = rememberTrackMetadata(listOf(rich), listOf(sparse)).single()
        assertEquals("Verified credit", saved.artist); assertEquals("Record", saved.album)
        assertEquals("Jazz", saved.genre); assertEquals("Unwind", saved.mood)
    }

    @Test fun refreshImmediatelyChangesPicksWithoutDuplicatesAndKeepsTwenty() {
        val songs = (0 until 40).map { song("refresh-$it") }
        val before = rotateQuickPicks(songs, 0)
        val after = rotateQuickPicks(songs + songs.first(), 1)
        assertEquals(20, after.size); assertEquals(20, after.map { it.id }.distinct().size)
        assertNotEquals(before, after); assertTrue(after.any { it !in before })
        assertTrue(rotateQuickPicks(emptyList(), 4).isEmpty())
        assertEquals(3, rotateQuickPicks(songs.take(3), Int.MAX_VALUE).size)
    }
    @Test fun yesterdayListeningDoesNotReplayTheSameTwentyProviderSongs() {
        val old = (0 until 20).map { song("old-$it", "Artist ${it % 5}") }
        val fresh = (0 until 20).map { song("fresh-$it", "Artist ${it % 5}") }
        val history = FeatureState(history = old.map { ListenRecord(it.id, day.minusDays(1).toString(), 180_000, 1) })
        val result = rankRecommendations(old + fresh, song("seed"), LibraryState(savedTracks = old), history,
            day, relatedIds = (old + fresh).map { it.id }.toSet())
        assertEquals(fresh.map { it.id }.toSet(), result.map { it.id }.toSet())
    }
    @Test fun refreshPrefersUnseenPicksAndCapsAnArtistWhenAlternativesExist() {
        val same = (0 until 30).map { song("same-$it", "Singer") }
        val others = (0 until 40).map { song("other-$it", "Other ${it % 6}") }
        val previous = (same.take(4) + others.take(16)).map { it.id }.toSet()
        val result = rankRecommendations(same + others, null, LibraryState(), FeatureState(), day, previousIds = previous)
        assertEquals(20, result.size)
        assertTrue(result.none { it.id in previous })
        assertTrue(result.groupingBy { it.artist }.eachCount().values.all { it <= 4 })
    }
    @Test fun dailyVarietyChangesOrderingEvenWithoutNewHistory() {
        val tracks = (0 until 40).map { song("daily-$it") }
        assertNotEquals(rankRecommendations(tracks, null, LibraryState(), FeatureState(), day),
            rankRecommendations(tracks, null, LibraryState(), FeatureState(), day.plusDays(1)))
    }

    @Test fun followedArtistsInfluenceRankingAndRetrievalWithoutListeningHistory() {
        val followed = song("followed", "Chosen artist")
        val features = FeatureState(experience = ExperiencePreferences(followedArtists = setOf("Chosen artist")))
        assertEquals(followed, rankRecommendations(listOf(song("other"), followed), null, LibraryState(), features, day).first())
        assertEquals(listOf("Chosen artist"), preferredArtists(LibraryState(), features, day))
    }

    @Test fun preferredArtistSeedsUseEvidenceInsteadOfDailyRotationAndForgetOldSkips() {
        val favorite = song("favorite", "Favorite")
        val old = song("old", "Old taste")
        val blocked = song("blocked", "Blocked singer")
        val library = LibraryState(savedTracks = listOf(favorite, old, blocked), favorites = setOf(favorite.id, blocked.id))
        val features = FeatureState(history = listOf(
            ListenRecord(favorite.id, day.minusDays(300).toString(), skips = 8),
            ListenRecord(old.id, day.minusDays(300).toString(), milliseconds = 6_000_000)), lessLikeIds = setOf(blocked.id))
        assertEquals("Favorite", preferredArtists(library, features, day).first())
        assertFalse(preferredArtists(library, features, day).contains("Blocked singer"))
        assertEquals(preferredArtists(library, features, day), preferredArtists(library, features, day.plusDays(1)))
    }

    @Test fun quickPickRadiosUseRealListeningAndLikesWithoutSavedSearchesOrSkippedSongs() {
        val heard = song("heard", "My artist").copy(source = Source.YOUTUBE)
        val liked = song("liked", "Other favorite").copy(source = Source.YOUTUBE)
        val skipped = song("skip", "Skipped artist").copy(source = Source.YOUTUBE)
        val unseen = song("unseen", "Search result").copy(source = Source.YOUTUBE)
        val local = song("local").copy(source = Source.LOCAL)
        val library = LibraryState(savedTracks = listOf(heard, liked, skipped, unseen, local),
            favorites = setOf(liked.id, local.id), recentIds = listOf(heard.id))
        val feedback = FeatureState(history = listOf(ListenRecord(heard.id, day.toString(), 60_000, 1),
            ListenRecord(skipped.id, day.toString(), 5_000, 1, 1)))
        assertEquals(listOf(heard, liked), quickPickSeeds(library, feedback))
        assertEquals(listOf(liked), quickPickSeeds(library, feedback.copy(lessLikeIds = setOf(heard.id))))
    }

}
