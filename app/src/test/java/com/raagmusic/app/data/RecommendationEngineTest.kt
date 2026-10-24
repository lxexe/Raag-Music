package com.raagmusic.app.data

import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.FeatureState
import com.raagmusic.app.domain.LibraryState
import com.raagmusic.app.domain.ListenRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecommendationEngineTest {
    private class Engine : ExtractionEngine {
        val queries = mutableListOf<String>()
        val related = (0 until 30).map { Track("r%010d".format(it), "Radio $it", "Singer", "", 180) }
        val discovery = (0 until 40).map { Track("d%010d".format(it), "Discovery $it", "Other ${it % 8}", "", 180) }
        override fun relatedSongs(trackId: String) = related
        override fun searchSongs(query: String): List<Track> { queries += query; return discovery }
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<Track>()
        override fun audioStreamUrl(trackId: String): String = error("No playback in a recommendation test")
    }
    private val seed = Track("abcdefghijk", "Seed", "Singer", "", 180).toLibraryTrack()
    @Test fun eightDiverseSuccessorsDoNotWaitForUnnecessaryDiscoverySearches() = runBlocking {
        val source = Engine()
        val ready = object : ExtractionEngine by source {
            override fun relatedSongs(trackId: String) = source.discovery.take(8)
        }
        val result = RecommendationEngine(YoutubeMusicRepository(ready)).next(seed, LibraryState(), FeatureState())
        assertEquals(8, result.size)
        assertTrue("Playable successors should publish before optional searches", source.queries.isEmpty())
    }
    @Test fun dominantSingerRadioAcquiresOtherArtistsAndUsesTheRepositoriesRegion() = runBlocking {
        val source = Engine()
        val engine = RecommendationEngine(YoutubeMusicRepository(source, initialCountry = "IN"))
        val result = engine.next(seed, LibraryState(), FeatureState())
        assertEquals(20, result.size)
        assertTrue(source.queries.any { it.contains("India") })
        assertTrue(result.count { it.artist == "Singer" } <= 4)
        assertTrue(result.map { it.artist }.distinct().size >= 5)
    }
    @Test fun yesterdayRadioTriggersDiscoveryAndQueuedTracksRemainExcluded() = runBlocking {
        val source = Engine()
        val yesterday = LocalDate.now().minusDays(1).toString()
        val history = FeatureState(history = source.related.map { ListenRecord(it.id, yesterday, 180_000, 1) })
        val excluded = source.discovery.take(5).map { it.id }.toSet()
        val result = RecommendationEngine(YoutubeMusicRepository(source)).next(seed,
            LibraryState(savedTracks = source.related.map { it.toLibraryTrack() }), history, excluded)
        assertEquals(20, result.size)
        assertTrue(source.queries.isNotEmpty())
        assertTrue(result.none { it.id in excluded })
        assertTrue(result.count { it.id.startsWith("d") } >= 16)
    }
}
