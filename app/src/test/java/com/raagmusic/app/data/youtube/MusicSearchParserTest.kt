package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class MusicSearchParserTest {
    private val artistId = "UCabcdefghijklmnopqrstuv"
    @Test fun unfilteredSearchKeepsArtistTopResultAndPlayableSongs() {
        val raw = """{"contents":[
          {"musicCardShelfRenderer":{"title":{"runs":[{"text":"Alex"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"$artistId"}}}},
          {"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"A song"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Alex","navigationEndpoint":{"browseEndpoint":{"browseId":"$artistId"}}}]}}}],"playlistItemData":{"videoId":"abcdefghijk"}}},
          {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"A record"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abcdefghijk"}}}}
        ]}"""
        val page = MusicSearchParser.parse(raw)
        assertEquals("Alex", page.artists.single().name)
        assertEquals("Alex", page.tracks.single().artist)
        assertEquals("A record", page.collections.single().title)
        assertEquals(listOf("artist:$artistId", "track:abcdefghijk", "collection:ALBUM:MPREb_abcdefghijk"), page.order)
    }
    @Test fun artistBrowseCatalogSurvivesMissingHeaderRenderer() {
        val raw = """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"The album"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abcdefghijk"}}}}"""
        val profile = ArtistProfileParser.musicHeader(raw, ArtistProfile("Alex", channelUrl = "https://www.youtube.com/channel/$artistId"))
        assertEquals("The album", profile.albums.single().title)
        assertTrue(profile.channelUrl.endsWith(artistId))
    }
    @Test fun musicVideosRemainDistinctFromAudioSongs() {
        val raw = """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Music video"}]}}}],"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}}}}}"""
        val page = MusicSearchParser.parse(raw)
        assertTrue(page.tracks.isEmpty())
        assertEquals("abcdefghijk", page.videos.single().id)
    }
}
