package com.raagmusic.app.domain

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test fun roamingUsesCurrentNetworkBeforeSimAndDeviceRegion() {
        assertEquals(MusicRegion("JP", CountrySource.NETWORK), chooseMusicRegion("jp", "IN", listOf("US")))
    }
    @Test fun offlinePhoneUsesSimAndTabletUsesFirstValidDeviceRegion() {
        assertEquals(MusicRegion("IN", CountrySource.SIM), chooseMusicRegion("", "in", listOf("US")))
        assertEquals(MusicRegion("BR", CountrySource.DEVICE), chooseMusicRegion(null, null, listOf("", "ZZ", "br", "US")))
    }
    @Test fun invalidDeviceSignalsUseWorldwideWithoutInventingALocation() {
        assertEquals(MusicRegion(), chooseMusicRegion("ZZ", "unknown", listOf("419", "")))
        assertEquals("Worldwide", MusicRegion().displayName(Locale.ENGLISH))
        assertEquals("global", MusicRegion().queryName)
        assertNull(validCountryCode("null"))
    }
    @Test fun localizedDisplayNameDoesNotChangeRegionalProviderQueries() {
        val region = MusicRegion("IN", CountrySource.DEVICE)
        assertEquals("India", region.queryName)
        assertEquals("🇮🇳", region.flag)
        assertEquals("IN", region.providerCode)
        val queries = homeQueries(region, "For you", LocalDate.of(2026, 10, 2))
        assertEquals("Top songs India", queries.playlists)
        assertEquals("new songs India October 2026", queries.newReleases)
        assertTrue(queries.albums.contains("India"))
    }
    @Test fun moodsRetainCountryAndDateRatherThanFallingBackToUs() {
        val queries = homeQueries(MusicRegion("BR"), "Focus", LocalDate.of(2027, 1, 2))
        assertEquals("instrumental focus music Brazil", queries.playlists)
        assertEquals("instrumental focus songs Brazil", queries.trending)
        assertEquals("new songs Brazil January 2027", queries.newReleases)
    }
    @Test fun recentSearchesMoveToFrontWithoutCaseInsensitiveDuplicates() {
        val state = DiscoveryPreferences(countryOverride = "IN").withSearch("  Daft   Punk ").withSearch("Raag").withSearch("daft punk")
        assertEquals(listOf("daft punk", "Raag"), state.recentSearches)
        assertEquals("IN", state.countryOverride)
        assertEquals(state, state.withSearch("   "))
    }
    @Test fun moodAndCustomChipsActuallyFilterRegionalDiscovery() {
        val region = MusicRegion("IN")
        val date = LocalDate.of(2026, 10, 6)
        assertEquals("romantic love songs India", homeQueries(region, "Romance", date).trending)
        assertEquals("party hits songs India", homeQueries(region, "Party", date).trending)
        assertEquals("indie rock songs India", homeQueries(region, "Indie rock", date).trending)
        assertNotEquals(homeQueries(region, "Dance", date).trending, homeQueries(region, "Romance", date).trending)
    }
    @Test fun recentSearchesAreBoundedAndKeepNewestQueries() {
        val state = (1..30).fold(DiscoveryPreferences()) { state, i -> state.withSearch("Song $i") }
        assertEquals(12, state.recentSearches.size)
        assertEquals("Song 30", state.recentSearches.first())
        assertEquals("Song 19", state.recentSearches.last())
        assertEquals(120, state.withSearch("x".repeat(200)).recentSearches.first().length)
    }
}
