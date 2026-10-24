package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class MusicAlbumParserTest {
    @Test fun songsReferencingAnAlbumAreNotAlbumCards() {
        val raw = """{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk"}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song title"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"An album","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abcdefghijk"}}}]}}}]}}"""
        assertTrue(MusicAlbumParser.parse(raw).isEmpty())
    }
    @Test fun resolvesBrowseEndpointWithoutInventingAPlaylistId() {
        val raw = """{"contents":[{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abcdefghijk"}},"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album title"}]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Album"},{"text":" • "},{"text":"Artist name","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}},{"text":"2026"}]}}}],"thumbnail":{"thumbnails":[{"url":"https://example.org/low","height":50},{"url":"https://example.org/high","height":200}]}}}]}"""
        val result = MusicAlbumParser.parse(raw).single()
        assertEquals("Album title", result.title); assertEquals("Artist name", result.artist)
        assertEquals("https://music.youtube.com/browse/MPREb_abcdefghijk", result.url)
        assertEquals("https://example.org/high", result.thumbnailUrl)
    }
    @Test fun ignoresOtherBrowsePagesAndEmptyRenderers() {
        assertTrue(MusicAlbumParser.parse("""{"musicResponsiveListItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"UCnotanalbum"}}}}""").isEmpty())
        assertTrue(MusicAlbumParser.parse("""{"musicTwoRowItemRenderer":{"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_abcdefghijk"}}}}""").isEmpty())
    }
}
