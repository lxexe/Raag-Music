package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class SearchPresentationTest {
    @Test fun exactArtistAndAlbumCanRankAboveSongsAndCompletions() {
        val artist = com.raagmusic.app.data.youtube.ArtistProfile("Alex", channelUrl = "https://www.youtube.com/channel/alex")
        val weakSong = Track("song", "Another song", "Alex", "", "song", Source.YOUTUBE)
        val entries = rankedSearchEntries("Alex", listOf(weakSong), emptyList(), listOf(artist), emptyList(), listOf("Alex music"))
        assertTrue(entries.first() is SearchEntry.Artist)
        val album = com.raagmusic.app.data.youtube.BrowseCollection("album", "The record", "Alex", "", "", 0, com.raagmusic.app.data.youtube.CollectionKind.ALBUM)
        assertTrue(rankedSearchEntries("The record", listOf(weakSong), emptyList(), listOf(artist), listOf(album)).first() is SearchEntry.CollectionResult)
    }
    @Test fun equallyRelevantResultsRetainProviderOrderAcrossTypes() {
        val artist = com.raagmusic.app.data.youtube.ArtistProfile("Alex", channelUrl = "https://www.youtube.com/channel/alex")
        val song = Track("one", "Alex", "Another artist", "", "one", Source.YOUTUBE)
        val entries = rankedSearchEntries("Alex", listOf(song), emptyList(), listOf(artist), emptyList(), providerOrder = listOf("artist:alex", "track:one"))
        assertEquals(listOf("artist:alex", "track:one"), entries.map { it.key })
    }
    @Test fun usefulCompletionsCanRankAboveUnrelatedMatchesWithoutDroppingEither() {
        val song = Track("one", "Unrelated", "Other", "", "one", Source.YOUTUBE)
        val entries = rankedSearchEntries("Alex", listOf(song, song), emptyList(), emptyList(), emptyList(), listOf("Alex music"))
        assertTrue(entries.first() is SearchEntry.Suggestion)
        assertEquals(2, entries.size)
    }
}
