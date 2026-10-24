package com.raagmusic.app.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PersistentDiscoveryTest {
    @Test fun restartUsesRegionScopedSuggestionsAndRawRadioCandidates() = runBlocking {
        val directory = Files.createTempDirectory("discovery-test").toFile()
        var calls = 0
        val engine = object : ExtractionEngine {
            override fun searchSuggestions(query: String): List<String> { calls++; return listOf("fixture suggestion") }
            override fun relatedSongs(trackId: String): List<Track> { calls++; return listOf(Track("nextSong001", "Next", "Artist", "", 180)) }
            override fun audioStreamUrl(trackId: String): String = error("No audio")
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
            override fun collectionTracks(url: String) = emptyList<Track>()
        }
        try {
            val original = YoutubeMusicRepository(engine, initialCountry = "IN", metadataDirectory = directory)
            original.searchSuggestions("fixture"); original.getRelatedSongs("firstSong01")
            assertEquals(2, calls)
            val restart = YoutubeMusicRepository(engine, initialCountry = "IN", metadataDirectory = directory)
            assertEquals(listOf("fixture suggestion"), restart.searchSuggestions("fixture"))
            assertEquals("nextSong001", restart.getRelatedSongs("firstSong01").single().id)
            assertEquals(2, calls)
            val elsewhere = YoutubeMusicRepository(engine, initialCountry = "US", metadataDirectory = directory)
            elsewhere.searchSuggestions("fixture"); elsewhere.getRelatedSongs("firstSong01")
            assertEquals(4, calls)
            restart.clearMetadataCache(); restart.searchSuggestions("fixture")
            assertEquals(5, calls)
        } finally { directory.deleteRecursively() }
    }
}
