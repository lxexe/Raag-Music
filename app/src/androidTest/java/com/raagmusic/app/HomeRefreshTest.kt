package com.raagmusic.app

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class HomeRefreshTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication

    private class Engine : ExtractionEngine {
        val calls = AtomicInteger()
        val queries = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var gate: CountDownLatch? = null
        @Volatile var fail = false
        @Volatile var singer: String? = null
        override fun relatedSongs(trackId: String): List<com.raagmusic.app.data.youtube.Track> = if (singer == null) emptyList() else
            (0 until 25).map { index -> com.raagmusic.app.data.youtube.Track("r%010d".format(index), "Related song $index", "Related artist ${index % 5}", "", 180) }

        override fun searchSongs(query: String): List<com.raagmusic.app.data.youtube.Track> {
            val batch = calls.incrementAndGet()
            queries.add(query)
            gate?.await(8, TimeUnit.SECONDS)
            if (fail) throw org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("Fixture unavailable")
            return (0 until 30).map { index -> com.raagmusic.app.data.youtube.Track(
                "b%05d%05d".format(batch, index), "Batch $batch song $index", if (singer != null && query.contains(singer!!)) singer!! else "Artist ${index % 5}", "", 180) }
        }
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
        override fun audioStreamUrl(trackId: String): String = error("No audio request in Home test")
    }

    private suspend fun eventually(block: () -> Boolean) = withTimeout(12_000) { while (!block()) delay(25) }

    private suspend fun withHome(initialLibrary: LibraryState = LibraryState(), configure: (Engine) -> Unit = {},
        block: suspend CoroutineScope.(MusicViewModel, Engine) -> Unit) = coroutineScope {
        val library = app.library.state.first()
        val features = app.features.state.first()
        val snapshot = File(app.cacheDir, "home-discovery-v2.json")
        val cached = snapshot.takeIf { it.exists() }?.readBytes()
        val store = ViewModelStore()
        val engine = Engine().apply(configure)
        lateinit var vm: MusicViewModel
        try {
            snapshot.delete()
            app.library.update { initialLibrary }
            app.features.update { FeatureState(discovery = DiscoveryPreferences(countryOverride = "IN"),
                preferences = ListeningPreferences(rememberQueue = false)) }
            withContext(Dispatchers.Main) { vm = MusicViewModel(app, YoutubeMusicRepository(engine)); store.put("home-test", vm) }
            block(vm, engine)
        } finally {
            engine.gate?.countDown()
            withContext(Dispatchers.Main) { store.clear() }
            app.library.update { library }; app.features.update { features }
            if (cached == null) snapshot.delete() else snapshot.writeBytes(cached)
        }
    }

    @Test fun interruptedInitialDiscoveryResumesEvenWhenLocalPicksAreAlreadyVisible() = runBlocking {
        val known = com.raagmusic.app.domain.Track("knownSong01", "Known", "Preferred Singer", "", "knownSong01", Source.YOUTUBE, durationSeconds = 180)
        withHome(LibraryState(savedTracks = listOf(known), favorites = setOf(known.id)), configure = { it.gate = CountDownLatch(1) }) { vm, engine ->
            eventually { engine.calls.get() == 1 && vm.homeSongs.value.items.isNotEmpty() }
            withContext(Dispatchers.Main) { vm.pauseHomeLoading() }
            assertFalse(vm.homeSongs.value.loading)
            engine.gate?.countDown(); engine.gate = null
            withContext(Dispatchers.Main) { vm.resumeHomeIfNeeded() }
            eventually { !vm.homeSongs.value.loading && vm.homeSongs.value.items.size == 20 }
            assertTrue(engine.calls.get() > 1)
        }
    }

    @Test fun onePullPublishesOneBatchAndOtherShelvesCannotReorderIt() = runBlocking {
        withHome { vm, engine ->
            val observed = Collections.synchronizedList(mutableListOf<List<String>>())
            val collect = launch { vm.quickPicks.map { state -> state.items.map { it.id } }.distinctUntilChanged().collect { observed.add(it) } }
            eventually { vm.quickPicks.value.items.size == 20 && !vm.homeSongs.value.loading }
            val before = vm.quickPicks.value.items.map { it.id }
            observed.clear()
            val gate = CountDownLatch(1); engine.gate = gate
            withContext(Dispatchers.Main) { vm.loadHome(refresh = true); vm.loadHome(refresh = true) }
            eventually { engine.calls.get() == 2 }
            assertTrue(vm.homeRefreshing.value)
            assertEquals("Retain the previous picks while the first page loads", before, vm.quickPicks.value.items.map { it.id })
            engine.gate = null; gate.countDown()
            eventually { !vm.homeRefreshing.value && vm.quickPicks.value.items.map { it.id } != before }
            val after = vm.quickPicks.value.items.map { it.id }
            assertEquals(20, after.size); assertEquals(20, after.distinct().size)
            assertEquals("One first page per discovery source in the pull", 3, engine.calls.get())
            withContext(Dispatchers.Main) { vm.requestHomeSection("trending"); vm.requestHomeSection("releases") }
            eventually { engine.calls.get() == 5 && !vm.homeNewReleases.value.loading }
            delay(100)
            assertEquals("Shelf arrivals keep the quick picks stable", after, vm.quickPicks.value.items.map { it.id })
            assertEquals("A single visible content replacement", listOf(after), observed.toList())
            collect.cancelAndJoin()
        }
    }

    @Test fun failedPullRetainsPicksAndMoodChipsUseTheirOwnQuery() = runBlocking {
        withHome { vm, engine ->
            val collect = launch { vm.quickPicks.collect() }
            eventually { vm.quickPicks.value.items.size == 20 && !vm.homeSongs.value.loading }
            val before = vm.quickPicks.value.items.map { it.id }
            engine.fail = true
            withContext(Dispatchers.Main) { vm.loadHome(refresh = true) }
            eventually { !vm.homeRefreshing.value && vm.homeSongs.value.error != null }
            assertEquals(before, vm.quickPicks.value.items.map { it.id })
            engine.fail = false
            withContext(Dispatchers.Main) { vm.loadHome("Romance") }
            eventually { !vm.homeSongs.value.loading && vm.homeSongs.value.items.isNotEmpty() }
            assertEquals("romantic love songs India", engine.queries.last())
            withContext(Dispatchers.Main) { vm.loadHome("Ghazal") }
            eventually { !vm.homeSongs.value.loading && vm.homeSongs.value.items.isNotEmpty() }
            assertEquals("ghazal songs India", engine.queries.last())
            collect.cancelAndJoin()
        }
    }
    @Test fun personalizedRefreshMixesRelatedArtistsWithoutRegionalFillers() = runBlocking {
        withHome { vm, engine ->
            val collect = launch { vm.quickPicks.collect() }
            eventually { vm.quickPicks.value.items.size == 20 && !vm.homeSongs.value.loading }
            engine.singer = "Preferred Singer"
            val known = com.raagmusic.app.domain.Track("knownSinger", "Known", "Preferred Singer", "", "", Source.YOUTUBE, durationSeconds = 180)
            app.library.update { it.copy(savedTracks = listOf(known), favorites = setOf(known.id)) }
            val before = engine.queries.size
            withContext(Dispatchers.Main) { vm.loadHome(refresh = true) }
            eventually { !vm.homeRefreshing.value && engine.queries.any { it.contains("Preferred Singer") } }
            val batch = vm.quickPicks.value.items
            assertEquals(20, batch.size)
            assertTrue(batch.count { it.artist == "Preferred Singer" } <= 4)
            assertTrue(batch.map { it.artist }.distinct().size >= 5)
            assertTrue("Personal retrieval takes priority over generic discovery", engine.queries[before].contains("Preferred Singer"))
            assertTrue("Personalized picks do not query unrelated regional trends", engine.queries.drop(before).all { it == "Preferred Singer songs" })
            collect.cancelAndJoin()
        }
    }

}
