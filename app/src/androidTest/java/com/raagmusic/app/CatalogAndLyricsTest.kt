package com.raagmusic.app

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.LyricsProvider
import com.raagmusic.app.data.LyricsRepository
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CatalogAndLyricsTest {
    @Test fun lyricsStartBeforeOpeningPlayerAndRemainReadyWhenReopened() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val features = app.features.state.first(); val library = app.library.state.first()
        val calls = AtomicInteger()
        val lyrics = LyricsRepository(listOf(LyricsProvider { delay(100); calls.incrementAndGet(); Lyrics(listOf(LyricLine(0, "Ready before opening")), provider = "Fixture") }))
        val engine = object : ExtractionEngine {
            override fun searchSongs(query: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
            override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
            override fun collectionTracks(url: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
            override fun audioStreamUrl(trackId: String) = error("Unused")
        }
        val store = ViewModelStore()
        lateinit var vm: MusicViewModel
        val fixture = com.raagmusic.app.domain.Track("prefetch-lyrics-fixture", "Prefetch", "Fixture", "", "file:///not-playing.wav", Source.LOCAL)
        try {
            app.features.update { FeatureState(profile = ListenerProfile(namePromptSeen = true), queue = QueueSnapshot(listOf(fixture))) }
            app.library.update { LibraryState(savedTracks = listOf(fixture)) }
            withContext(Dispatchers.Main) { vm = MusicViewModel(app, YoutubeMusicRepository(engine), lyrics); store.put("lyrics", vm); vm.player.play(fixture, listOf(fixture), ordered = true); vm.player.pause() }
            withTimeout(5000) { while (vm.lyrics.value.lyrics?.provider != "Fixture") delay(30) }
            assertEquals(fixture.id, vm.lyrics.value.trackId)
            withContext(Dispatchers.Main) { vm.loadLyrics(fixture); vm.loadLyrics(fixture) }
            delay(200)
            assertEquals("Opening/reopening reuses prefetched lyrics", 1, calls.get())
        } finally {
            withContext(Dispatchers.Main) { vm.player.pause(); vm.removeQueue(vm.playback.value.queueIds.indices.toSet()); store.clear() }
            delay(300); app.features.update { features }; app.library.update { library }
        }
    }

    @Test fun liveUnfilteredSearchAndBoundArtistAlbums() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCatalog") == "true")
        val repository = YoutubeMusicRepository(initialCountry = "IN")
        val page = repository.searchPage("Arijit Singh", "All", foreground = true)
        assertTrue("Unfiltered search must include artist matches", page.artists.isNotEmpty())
        val selected = page.artists.firstOrNull { ArtistNames.same(it.name, "Arijit Singh") }
            ?: repository.getArtistProfileByName("Arijit Singh") ?: error("Artist not found")
        val details = repository.getArtistDetails(selected)
        android.util.Log.i("GeyamCatalog", "allSongs=${page.tracks.size} artists=${page.artists.size} collections=${page.collections.size} selected=${details.channelUrl} albums=${details.albums.size} popular=${details.popularSongs.size}")
        assertTrue("Selected artist Music page should expose albums", details.albums.isNotEmpty())
        assertTrue("Albums should retain browse identities", details.albums.all { it.id.startsWith("MPRE") })
    }
}
