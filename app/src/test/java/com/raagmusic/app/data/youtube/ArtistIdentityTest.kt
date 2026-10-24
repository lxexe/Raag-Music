package com.raagmusic.app.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtistIdentityTest {
    private class Engine : ExtractionEngine {
        var nameLookups = 0
        override fun artistProfileByName(name: String): ArtistProfile? { nameLookups++; return ArtistProfile("Alex", channelUrl = "wrong") }
        override fun songArtistCredits(trackId: String) = SongArtistCredits(listOf("Alex"), linkedArtists = listOf(
            ArtistProfile("Alex", channelUrl = "https://www.youtube.com/channel/" + if (trackId == "abcdefghijk") "UCaaaaaaaaaaaaaaaaaaaaaa" else "UCbbbbbbbbbbbbbbbbbbbbbb")))
        override fun searchSongs(query: String) = emptyList<Track>()
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<Track>()
        override fun audioStreamUrl(trackId: String) = error("Unused")
    }
    @Test fun identicalArtistNamesResolveThroughEachSongsOwnChannel() = runBlocking {
        val engine = Engine(); val repository = YoutubeMusicRepository(engine)
        val first = repository.getSongArtistProfile("abcdefghijk", "Alex")!!
        val second = repository.getSongArtistProfile("lmnopqrstuv", "Alex")!!
        assertNotEquals(first.channelUrl, second.channelUrl)
        assertEquals(0, engine.nameLookups)
    }
    @Test fun linksPreserveProviderIdsRatherThanDeduplicatingArtistsByName() {
        val raw = """{"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","longBylineText":{"runs":[
            {"text":"Alex","navigationEndpoint":{"browseEndpoint":{"browseId":"UCaaaaaaaaaaaaaaaaaaaaaa"}}},
            {"text":"Alex","navigationEndpoint":{"browseEndpoint":{"browseId":"UCbbbbbbbbbbbbbbbbbbbbbb"}}}]}}}"""
        assertEquals(2, MusicArtistCredits.linked(raw, "abcdefghijk").size)
    }
}
