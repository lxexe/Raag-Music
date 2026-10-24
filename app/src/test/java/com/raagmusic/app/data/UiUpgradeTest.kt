package com.raagmusic.app.data

import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class UiUpgradeTest {
    private val song = Track("abcdefghijk", "Song", "Artist", "", "abcdefghijk", Source.YOUTUBE)
    private val local = song.copy(id = "local-file", uri = "content://documents/audio/1", source = Source.LOCAL)

    @Test fun appearanceAndExperiencePersistAlongsideExistingQueue() {
        val state = FeatureState(queue = QueueSnapshot(listOf(song, local), 1, 4123),
            theme = ThemePreferences(motion = MotionLevel.FULL, haptics = false, effects = false,
                density = ContentDensity.COMPACT, playerStyle = PlayerStyle.VINYL, miniFavorite = false),
            experience = ExperiencePreferences(pinnedPlaylists = setOf("p"), followedArtists = setOf("Artist"), libraryGrid = true, lyricTextSize = 30,
                lyricBlur = false, lyricCentered = true, lyricOffsetMs = -1500, homeMoods = listOf("For you", "Road trip", "Ghazal")))
        assertEquals(state.copy(theme = state.theme.copy(playerStyle = PlayerStyle.COMPACT)), FeatureCodec.decode(FeatureCodec.encode(state)))
        assertEquals(ExperiencePreferences(), FeatureCodec.decode("{}").experience)
    }

    @Test fun oldPreferencesReceiveDefaultChipsAndCustomChipsSurviveRestart() {
        assertEquals(defaultHomeMoods, FeatureCodec.decode("{\"experience\":{\"lyricTextSize\":26}}").experience.homeMoods)
        val moods = ExperiencePreferences(homeMoods = listOf("  Road   trip ", "road trip", "", "FOR YOU", "Ghazal")).sanitized().homeMoods
        assertEquals(listOf("For you", "Road trip", "Ghazal"), moods)
        val state = FeatureState(experience = ExperiencePreferences(homeMoods = moods))
        assertEquals(moods, FeatureCodec.decode(FeatureCodec.encode(state)).experience.homeMoods)
    }

    @Test fun lyricsPreferencesAreBounded() {
        val prefs = ExperiencePreferences(lyricTextSize = 900, lyricOffsetMs = -90_000).sanitized()
        assertEquals(34, prefs.lyricTextSize); assertEquals(-10_000L, prefs.lyricOffsetMs)
    }

    @Test fun localStorageKeepsDocumentGrantsAndPlaylistMetadata() {
        val library = LibraryState(setOf(local.id), listOf(Playlist("p", "Offline", listOf(local.id),
            "Train journeys", "content://documents/cover/2", "Travel")), listOf(local), listOf(local.id))
        assertEquals(library, LibraryCodec.decode(LibraryCodec.encode(library)))
    }

    @Test fun portableBackupExcludesLocalDocumentsAndCustomGrantsWithoutDroppingPlaylistDetails() {
        val playlist = Playlist("p", "Mix", listOf(song.id, local.id), "Description", "content://documents/cover/2", "Travel")
        val library = LibraryState(setOf(song.id, local.id), listOf(playlist), listOf(song, local), listOf(local.id, song.id),
            mapOf(song.id to 10L, local.id to 20L))
        val backup = LibraryBackup.export(library)
        assertFalse(backup.contains("content://")); assertFalse(backup.contains(local.id))
        val restored = LibraryBackup.parse(backup)
        assertEquals(listOf(song), restored.savedTracks); assertEquals(setOf(song.id), restored.favorites)
        assertEquals(playlist.copy(trackIds = listOf(song.id), coverUri = ""), restored.playlists.single())
    }

    @Test fun recapFiltersMonthAndCountsStreakAcrossTheMonthBoundary() {
        val today = LocalDate.of(2026, 10, 2)
        val features = FeatureState(history = listOf(ListenRecord(song.id, "2026-09-30", 60_000, 1),
            ListenRecord(song.id, "2026-10-01", 120_000, 2), ListenRecord(song.id, "2026-10-02", 60_000, 1)))
        val recap = listeningRecap(features, LibraryState(savedTracks = listOf(song)), YearMonth.of(2026, 10), today)
        assertEquals(3L, recap.minutes); assertEquals(3, recap.plays); assertEquals(1, recap.songs)
        assertEquals(3, recap.streak); assertEquals(120_000L, recap.days[1]); assertEquals("Artist", recap.topArtists.single().label)
    }
}
