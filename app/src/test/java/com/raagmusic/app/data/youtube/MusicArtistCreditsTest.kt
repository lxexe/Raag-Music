package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class MusicArtistCreditsTest {
    @Test fun picksIndividualSingersFromCurrentSongAndIgnoresAlbumAndOtherSongs() {
        val raw = """{"contents":[
          {"playlistPanelVideoRenderer":{"videoId":"other_song1","longBylineText":{"runs":[
            {"text":"Wrong singer","navigationEndpoint":{"browseEndpoint":{"browseId":"UCother"}}}]}}},
          {"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","longBylineText":{"runs":[
            {"text":"Arijit Singh","navigationEndpoint":{"browseEndpoint":{"browseId":"UCArijit"}}},
            {"text":" & "},
            {"text":"Shreya Ghoshal","navigationEndpoint":{"browseEndpoint":{"browseId":"UCShreya"}}},
            {"text":"Album title","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREalbum"}}},
            {"text":"Record label","navigationEndpoint":{"browseEndpoint":{"browseId":"UCLabel",
              "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_USER_CHANNEL"}}}}}
          ]}}}]}"""
        assertEquals(listOf("Arijit Singh", "Shreya Ghoshal"), MusicArtistCredits.parse(raw, "abcdefghijk"))
        assertEquals(listOf("Wrong singer"), MusicArtistCredits.related(raw, "abcdefghijk"))
    }
    @Test fun preservesBandNamesAndSplitsExplicitFeaturedCredits() {
        assertEquals(listOf("Guns N' Roses"), ArtistNames.fallback("Guns N' Roses"))
        assertEquals(listOf("Simon & Garfunkel"), ArtistNames.fallback("Simon & Garfunkel"))
        assertEquals(listOf("Arijit Singh", "Shreya Ghoshal"), ArtistNames.fallback("Arijit Singh, Shreya Ghoshal"))
        assertEquals(listOf("Singer", "Guest"), ArtistNames.fallback("Singer feat. Guest"))
        assertTrue(ArtistNames.same("Arijit Singh - Topic", "arijit singh"))
        assertFalse(ArtistNames.same("T-Series", "Arijit Singh"))
    }
}
