package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class MusicRadioParserTest {
    @Test fun nativeRadioRetainsArtistAlbumDurationAndLargestArtwork() {
        val raw = """{"contents":[{"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"A song"}]},"lengthText":{"simpleText":"3:21"},"thumbnail":{"thumbnails":[{"url":"small","width":100},{"url":"large","width":600}]},"longBylineText":{"runs":[{"text":"Singer","navigationEndpoint":{"browseEndpoint":{"browseId":"UC123","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}},{"text":"Album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE123","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]}}}]}"""
        val song = MusicRadioParser.parse(raw).single()
        assertEquals("Singer", song.artist); assertEquals("Album", song.album)
        assertEquals(201L, song.durationSeconds); assertEquals("large", song.thumbnailUrl)
    }
    @Test fun missingPanelsAreEmptyAndInvalidIdsAreRejected() {
        assertTrue(MusicRadioParser.parse("{}" ).isEmpty())
        assertTrue(MusicRadioParser.parse("""{"playlistPanelVideoRenderer":{"videoId":"bad","title":{"simpleText":"Song"}}}""").isEmpty())
    }
}
