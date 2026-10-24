package com.raagmusic.app.data

import com.raagmusic.app.domain.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FeatureCodecTest {
    @Test fun analyticsVideoAndStyleChoicesPersistAndUpgradeSafely() {
        val old = FeatureCodec.decode("{}")
        assertTrue(old.preferences.analyticsEnabled)
        assertEquals(VideoPlaybackMode.FALLBACK, old.preferences.videoMode)
        assertEquals(VideoQuality.HIGHEST, old.preferences.videoQuality)
        val state = FeatureState(preferences = ListeningPreferences(analyticsEnabled = false, videoMode = VideoPlaybackMode.ALWAYS, videoQuality = VideoQuality.SD),
            theme = ThemePreferences(playerStyle = PlayerStyle.APPLE, lyricStyle = LyricStyle.APPLE))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
    }

    @Test fun dynamicThemePersistsAndHomeUpgradeRemovesSearchesButKeepsHistory() {
        val state = FeatureState(theme = ThemePreferences(ThemeMode.DYNAMIC),
            discovery = DiscoveryPreferences(recentSearches = listOf("Chill")))
        assertEquals(state.copy(theme = state.theme.copy(mode = ThemeMode.AMOLED)), FeatureCodec.decode(FeatureCodec.encode(state)))
        val migrated = FeatureCodec.decode("""{"experience":{"homeSections":["searches","moods","quick"],"hiddenHomeSections":["searches","artists"]},"discovery":{"recentSearches":["Chill"]}}""")
        assertEquals(ExperiencePreferences(), migrated.experience)
        assertEquals(listOf("Chill"), migrated.discovery.recentSearches)
    }

    @Test fun stopOnExitDefaultsOffAndPersistsWithoutLosingTheQueue() {
        assertFalse(FeatureCodec.decode("{}").preferences.stopOnExit)
        val state = FeatureState(preferences = ListeningPreferences(stopOnExit = true), queue = QueueSnapshot(listOf(track), positionMs = 12345))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
    }

    @Test fun recommendationChainPersistsAndOlderQueuesStayExplicit() {
        assertFalse(FeatureCodec.decode("""{"queue":{"tracks":[]}}""").queue.recommendationChain)
        val state = FeatureState(queue = QueueSnapshot(listOf(track), recommendationChain = true))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
    }
    @Test fun mobileQualityUpgradeDefaultsToHighAndLaterUserChoicesPersist() {
        val old = FeatureCodec.decode("""{"preferences":{"mobileQuality":"DATA_SAVER"}}""")
        assertEquals(AudioQuality.HIGH, old.preferences.mobileQuality)
        val choice = old.copy(preferences = old.preferences.copy(mobileQuality = AudioQuality.DATA_SAVER))
        assertEquals(choice, FeatureCodec.decode(FeatureCodec.encode(choice)))
    }
    @Test fun sleepTimerSurvivesRoundTripAndOlderPreferencesStartWithoutATimer() {
        assertEquals(SleepTimerSpec(), FeatureCodec.decode("{}").sleepTimer)
        listOf(SleepTimerSpec(999_999), SleepTimerSpec(finishTrackId = track.id)).forEach { timer ->
            val state = FeatureState(sleepTimer = timer)
            assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
        }
    }

    @Test fun optionalProfileMigratesAndPersistsWithoutChangingPlaybackOrTheme() {
        assertEquals(ListenerProfile(), FeatureCodec.decode("{}").profile)
        val state = FeatureState(profile = ListenerProfile("Lx", true),
            theme = ThemePreferences(ThemeMode.AMOLED), queue = QueueSnapshot(listOf(track)))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
        assertEquals(ListenerProfile("", true), FeatureCodec.decode(FeatureCodec.encode(
            state.copy(profile = ListenerProfile("", true)))).profile)
    }

    @Test fun countryAndSearchesSurviveUpgradeWithoutLosingExistingPreferences() {
        assertEquals(DiscoveryPreferences(), FeatureCodec.decode("{}").discovery)
        val state = FeatureState(theme = ThemePreferences(ThemeMode.AMOLED),
            discovery = DiscoveryPreferences("IN", listOf("Daft Punk", "Chill")))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
        val sanitized = FeatureCodec.decode("""{"discovery":{"country":"ZZ","recentSearches":["Daft Punk","","daft punk","   Chill   "]}}""")
        assertEquals(DiscoveryPreferences(null, listOf("Daft Punk", "Chill")), sanitized.discovery)
    }
    private val track = Track("abcdefghijk", "Song", "Artist", "", "abcdefghijk", Source.YOUTUBE)
    @Test fun queueOrderIncludingDuplicateSongsAndSettingsSurvivesRoundTrip() {
        val state = FeatureState(ListeningPreferences(wifiQuality = AudioQuality.BALANCED, collectHistory = false),
            QueueSnapshot(listOf(track, track), 1, 42_000, true, 2), lessLikeIds = setOf(track.id))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
    }
    @Test fun historyRoundTripKeepsListeningCounters() {
        val state = FeatureState(historyEpoch = 4).record(track.id, LocalDate.of(2026, 10, 2), 5000, 3, 1)
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
    }
    @Test fun unknownQualityAndMissingFieldsUseSafeDefaults() {
        val state = FeatureCodec.decode("""{"preferences":{"wifiQuality":"FUTURE"}}""")
        assertEquals(ListeningPreferences(), state.preferences); assertTrue(state.queue.tracks.isEmpty())
    }
    @Test fun audioAndLibraryPreferencesSurviveUpgradeAndRoundTrip() {
        val state = FeatureState(preferences = ListeningPreferences(transitionMode = TransitionMode.CROSSFADE, crossfadeSeconds = 8,
            equalizer = EqualizerSettings(true, EqualizerPreset.CUSTOM, listOf(1,2,3,4,5)), librarySort = LibrarySort.ARTIST, libraryFilter = LibraryFilter.LOCAL))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
        val old = FeatureCodec.decode("""{"preferences":{"crossfadeSeconds":999,"equalizer":{"preset":"UNKNOWN","gains":[99,-99]}}}""")
        assertEquals(12, old.preferences.crossfadeSeconds)
        assertEquals(listOf(12,-12,0,0,0), old.preferences.equalizer.customGains)
        assertEquals(EqualizerPreset.FLAT, old.preferences.equalizer.preset)
    }
    @Test fun snapshotsStoreStableIdsAndNeverYouTubeStreamLeases() {
        val raw = FeatureCodec.encode(FeatureState(queue = QueueSnapshot(listOf(track))))
        assertFalse(raw.contains("youtube://")); assertFalse(raw.contains("googlevideo")); assertFalse(raw.contains("play="))
    }
    @Test fun appearanceRoundTripDoesNotLosePlaybackOrListeningPreferences() {
        val state = FeatureState(preferences = ListeningPreferences(wifiQuality = AudioQuality.BALANCED),
            queue = QueueSnapshot(listOf(track, track), 1, 9876, true, 2),
            theme = ThemePreferences(ThemeMode.AMOLED, ThemeColors.CUSTOM, 0xFF2563EB, SurfaceStyle.GLASS, CornerStyle.ROUND, true))
        assertEquals(state, FeatureCodec.decode(FeatureCodec.encode(state)))
    }
    @Test fun oldAndUnknownAppearanceFieldsUseSafeDefaults() {
        assertEquals(ThemePreferences(), FeatureCodec.decode("{}").theme)
        val migrated = FeatureCodec.decode("""{"theme":{"mode":"FUTURE","colors":"FUTURE","surfaces":"FUTURE","corners":"FUTURE","accent":1193046}}""").theme
        assertEquals(ThemePreferences(accent = 0xFF123456), migrated)
    }
    @org.junit.Test fun queueSnapshotPreservesRecommendationMetadata() {
        val track = com.raagmusic.app.domain.Track("abcdefghijk", "Song", "Artist", "", "abcdefghijk",
            com.raagmusic.app.domain.Source.YOUTUBE, mood = "Romance", album = "Album", genre = "Pop")
        val state = com.raagmusic.app.domain.FeatureState(queue = com.raagmusic.app.domain.QueueSnapshot(listOf(track), recommendationChain = true))
        org.junit.Assert.assertEquals(state.queue, FeatureCodec.decode(FeatureCodec.encode(state)).queue)
    }

    @Test fun removedHomeShelvesMigrateOutOfSavedPreferences() {
        val migrated = FeatureCodec.decode("""{"experience":{"homeSections":["continue","quick","spotlight"],"hiddenHomeSections":["spotlight","artists"]}}""")
        assertEquals(ExperiencePreferences(), migrated.experience)
        val encoded = FeatureCodec.encode(migrated)
        assertFalse(encoded.contains("homeSections"))
        assertFalse(encoded.contains("hiddenHomeSections"))
    }

}
