package com.raagmusic.app

import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.PlaybackService
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SearchExperienceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication

    private open class Engine : ExtractionEngine {
        val searches: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val suggestions: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun searchSongs(query: String): List<com.raagmusic.app.data.youtube.Track> {
            searches.add(query)
            return listOf(com.raagmusic.app.data.youtube.Track("abcdefghijk", query, "Artist", "", 30))
        }
        override fun searchSuggestions(query: String): List<String> { suggestions.add(query); return listOf("$query music") }
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
        override fun audioStreamUrl(trackId: String) = error("Not needed")
    }

    private suspend fun withModel(engine: Engine, block: suspend (MusicViewModel) -> Unit) {
        val library = app.library.state.first()
        val features = app.features.state.first()
        val store = ViewModelStore()
        lateinit var vm: MusicViewModel
        try {
            // Search boundaries should not depend on recommendations saved by live-provider runs.
            app.library.update { LibraryState() }
            app.features.update { it.copy(queue = QueueSnapshot(), preferences = it.preferences.copy(rememberQueue = false)) }
            withContext(Dispatchers.Main) {
                vm = MusicViewModel(app, YoutubeMusicRepository(engine))
                store.put("search-test", vm)
            }
            block(vm)
        } finally {
            withContext(Dispatchers.Main) { vm.player.pause(); vm.removeQueue(vm.playback.value.queueIds.indices.toSet()) }
            delay(300)
            withContext(Dispatchers.Main) { store.clear() }
            app.stopService(Intent(app, PlaybackService::class.java))
            delay(300)
            app.library.update { library }
            app.features.update { features }
        }
    }

    private suspend fun eventually(block: () -> Boolean) = withTimeout(10_000) {
        while (!block()) delay(40)
    }

    @Test fun allSearchDoesNotEagerlyFetchOffscreenEntityGroups() = runBlocking {
        var collectionCalls = 0
        val engine = object : Engine() {
            override fun searchCollections(query: String, kind: CollectionKind): List<BrowseCollection> {
                collectionCalls++; return emptyList()
            }
        }
        withModel(engine) { vm ->
            withContext(Dispatchers.Main) { vm.search("adele hello") }
            eventually { !vm.searchLoading.value && vm.searchResults.value.isNotEmpty() }
            delay(1_500)
            assertEquals(listOf("adele hello"), engine.searches.filter { it == "adele hello" })
            assertEquals(0, collectionCalls)
            withContext(Dispatchers.Main) { vm.requestSearchGroup("Albums"); vm.requestSearchGroup("Albums") }
            eventually { collectionCalls == 1 }
        }
    }

    @Test fun localMatchesAndPersonalCompletionsAppearBeforeSlowRemoteSearch() = runBlocking {
        val engine = object : Engine() {
            override fun searchSongs(query: String): List<com.raagmusic.app.data.youtube.Track> {
                if (query == "ade") Thread.sleep(1_500)
                return super.searchSongs(query)
            }
        }
        withModel(engine) { vm ->
            val local = com.raagmusic.app.domain.Track("local-adele", "Hello", "Adele", "", "content://test/audio", Source.LOCAL)
            app.library.update { it.copy(savedTracks = listOf(local)) }
            eventually { vm.library.value.savedTracks.any { it.id == local.id } }
            val start = android.os.SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { vm.search("ade") }
            eventually { vm.searchResults.value.any { it.id == local.id } }
            val localMs = android.os.SystemClock.elapsedRealtime() - start
            assertTrue("Local matches should not wait for the 1500ms provider: $localMs", localMs < 1_000)
            assertTrue(vm.searchLoading.value)
            assertTrue(vm.searchSuggestions.value.contains("Adele"))
            android.util.Log.i("GeyamAudit", "localSearchMs=$localMs providerDelayMs=1500")
        }
    }

    @Test fun searchPaginationRetainsResultsOnFailureAndRetriesWithoutDuplicates() = runBlocking {
        NewPipeExtractionEngine() // Initialize the downloader; the fixture never fetches a provider page.
        val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getSearchExtractor("hello",
            listOf(org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        val cursor = SearchCursor(org.schabi.newpipe.extractor.Page("https://example.com/page-2"), extractor)
        val first = com.raagmusic.app.data.youtube.Track("abcdefghijk", "First", "Artist", "", 30)
        val second = first.copy(id = "lmnopqrstuv", title = "Second")
        var failPage = true
        val engine = object : Engine() {
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage {
                if (cursor == null) return SearchPage(tracks = listOf(first), next = cursorForTest)
                if (failPage) throw java.io.IOException("Page temporarily unavailable")
                return SearchPage(tracks = listOf(first, second))
            }
            val cursorForTest = cursor
        }
        withModel(engine) { vm ->
            withContext(Dispatchers.Main) { vm.changeSearchKind("Songs"); vm.search("hello") }
            eventually { !vm.searchLoading.value && vm.searchMore.value }
            withContext(Dispatchers.Main) { vm.loadMoreSearch(); vm.loadMoreSearch() }
            eventually { !vm.searchPageLoading.value && vm.searchPageError.value != null }
            assertEquals(listOf(first.id), vm.searchResults.value.map { it.id })
            assertTrue(vm.searchMore.value)
            failPage = false
            withContext(Dispatchers.Main) { vm.loadMoreSearch() }
            eventually { !vm.searchPageLoading.value && !vm.searchMore.value }
            assertEquals(listOf(first.id, second.id), vm.searchResults.value.map { it.id })
            assertNull(vm.searchPageError.value)
        }
    }

    @Test fun duplicateOnlyContinuationStopsEvenWhenProviderChangesItsToken() = runBlocking {
        NewPipeExtractionEngine()
        val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getSearchExtractor("duplicates", emptyList(), "")
        val song = com.raagmusic.app.data.youtube.Track("abcdefghijk", "First", "Artist", "", 180)
        var calls = 0
        val engine = object : Engine() {
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage {
                calls++
                return SearchPage(tracks = listOf(song), next = SearchCursor(
                    org.schabi.newpipe.extractor.Page("https://example.com/page-$calls"), extractor))
            }
        }
        withModel(engine) { vm ->
            withContext(Dispatchers.Main) { vm.changeSearchKind("Songs"); vm.search("duplicates") }
            eventually { !vm.searchLoading.value && vm.searchMore.value }
            val before = calls
            withContext(Dispatchers.Main) { vm.loadMoreSearch() }
            eventually { !vm.searchPageLoading.value && !vm.searchMore.value }
            repeat(5) { withContext(Dispatchers.Main) { vm.loadMoreSearch() } }
            assertEquals(before + 1, calls)
            assertEquals(listOf(song.id), vm.searchResults.value.map { it.id })
            assertNull(vm.searchPageError.value)
        }
    }

    @Test fun switchingToVideosCancelsOldPageAndClearsItsContinuation() = runBlocking {
        NewPipeExtractionEngine() // Initialize the downloader; the fixture never fetches a provider page.
        val extractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getSearchExtractor("hello", emptyList(), "")
        val cursor = SearchCursor(org.schabi.newpipe.extractor.Page("https://example.com/slow-page"), extractor)
        val pageStarted = AtomicBoolean(false)
        val engine = object : Engine() {
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage {
                if (cursor != null) { pageStarted.set(true); Thread.sleep(5000) }
                val track = com.raagmusic.app.data.youtube.Track("abcdefghijk", kind, "Artist", "", 30)
                return SearchPage(tracks = listOf(track), next = if (kind == "Songs") cursorForTest else null)
            }
            val cursorForTest = cursor
        }
        withModel(engine) { vm ->
            withContext(Dispatchers.Main) { vm.changeSearchKind("Songs"); vm.search("hello") }
            eventually { !vm.searchLoading.value && vm.searchMore.value }
            withContext(Dispatchers.Main) { vm.loadMoreSearch() }
            eventually { pageStarted.get() }
            withContext(Dispatchers.Main) { vm.changeSearchKind("Videos") }
            eventually { !vm.searchLoading.value && vm.searchResults.value.firstOrNull()?.title == "Videos" }
            assertFalse(vm.searchMore.value)
            assertFalse(vm.searchPageLoading.value)
            assertNull(vm.searchPageError.value)
            withContext(Dispatchers.Main) { vm.clearSearch() }
            assertTrue(vm.searchResults.value.isEmpty())
            assertFalse(vm.searchMore.value)
        }
    }

    @Test fun playerCreditsUseAvailableMetadataAndPortraitRequestsSurviveScrolling() = runBlocking {
        var creditCalls = 0
        var portraitCalls = 0
        val engine = object : Engine() {
            override fun songArtistCredits(trackId: String): SongArtistCredits {
                creditCalls++
                Thread.sleep(200)
                return SongArtistCredits(listOf("Arijit Singh", "Shreya Ghoshal"))
            }
            override fun artistProfileByName(name: String): ArtistProfile {
                portraitCalls++
                Thread.sleep(200)
                return ArtistProfile(name, imageUrl = "portrait-$name")
            }
        }
        withModel(engine) { vm ->
            val track = com.raagmusic.app.domain.Track("abcdefghijk", "Duet", "Record label", "", "abcdefghijk", Source.YOUTUBE)
            withContext(Dispatchers.Main) { vm.requestSongArtists(track); vm.requestSongArtists(track) }
            assertEquals(listOf("Record label"), vm.cachedArtists(track))
            eventually { vm.songCredits.value[track.id]?.size == 1 }
            assertEquals(listOf("Record label"), vm.songCredits.value[track.id])
            withContext(Dispatchers.Main) { vm.requestSongArtists(track); vm.requestPortrait("Arijit Singh"); vm.requestPortrait("Arijit Singh") }
            eventually { vm.portraits.value["Arijit Singh"] == "portrait-Arijit Singh" }
            withContext(Dispatchers.Main) { vm.requestPortrait("Arijit Singh") }
            eventually { vm.songCredits.value[track.id]?.size == 2 }
            assertEquals(1, creditCalls)
            assertEquals(1, portraitCalls)
        }
    }

    @Test fun artistPagesUseSelectedSingerInsteadOfSeedArtworkOrUploader() = runBlocking {
        val lookedUp = Collections.synchronizedList(mutableListOf<String>())
        val engine = object : Engine() {
            override fun artistProfile(trackId: String): ArtistProfile = error("Seed uploader is not an artist identity")
            override fun songArtistCredits(trackId: String) = SongArtistCredits(listOf("Arijit Singh", "Shreya Ghoshal"), linkedArtists = listOf(
                ArtistProfile("Arijit Singh", imageUrl = "Arijit Singh-portrait", channelUrl = "https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"),
                ArtistProfile("Shreya Ghoshal", imageUrl = "Shreya Ghoshal-portrait", channelUrl = "https://www.youtube.com/channel/UCbbbbbbbbbbbbbbbbbbbbbb")))
            override fun artistProfileByName(name: String): ArtistProfile {
                lookedUp += name
                return ArtistProfile(name, imageUrl = "$name-portrait", channelUrl = "$name-channel")
            }
        }
        withModel(engine) { vm ->
            val seed = com.raagmusic.app.domain.Track("abcdefghijk", "Duet", "Arijit Singh, Shreya Ghoshal",
                "shared-album-cover", "abcdefghijk", Source.YOUTUBE)
            withContext(Dispatchers.Main) { vm.openArtist("Arijit Singh", seed) }
            eventually { vm.artist.value?.profileLoading == false }
            assertEquals("Arijit Singh", vm.artist.value?.profile?.name)
            assertEquals("Arijit Singh-portrait", vm.artist.value?.imageUrl)
            withContext(Dispatchers.Main) { vm.openArtist("Shreya Ghoshal", seed) }
            eventually { vm.artist.value?.profileLoading == false }
            assertEquals("Shreya Ghoshal", vm.artist.value?.profile?.name)
            assertEquals("Shreya Ghoshal-portrait", vm.artist.value?.imageUrl)
            assertTrue("Song-bound artists must never be searched by name", lookedUp.isEmpty())
        }
    }

    @Test fun nativeBrowseLoadsSectionsAndKeepsSearchEmpty() = runBlocking {
        val engine = object : Engine() {
            override fun searchCollections(query: String, kind: CollectionKind) = listOf(
                BrowseCollection("${kind.name}-collection", query, "Artist", "", "https://www.youtube.com/playlist?list=collection", 10, kind))
        }
        withModel(engine) { vm ->
            withContext(Dispatchers.Main) { vm.openBrowse("Workout", "workout music", mood = true) }
            eventually { vm.browse.value?.let { !it.songs.loading && !it.trending.loading && !it.releases.loading && !it.playlists.loading && !it.albums.loading } == true }
            val page = vm.browse.value!!
            assertEquals("Workout", page.title)
            assertTrue(page.songs.items.isNotEmpty()); assertTrue(page.trending.items.isNotEmpty())
            assertTrue(page.releases.items.isNotEmpty()); assertTrue(page.playlists.items.isNotEmpty())
            assertTrue(page.albums.items.isNotEmpty())
            assertEquals("Workout", page.songs.items.first().mood)
            assertTrue(vm.searchResults.value.isEmpty()); assertEquals("", vm.searchQuery.value)
        }
    }

    @Test fun changingBrowseCancelsStaleCategoryWithoutChangingQueue() = runBlocking {
        val started = AtomicBoolean(false)
        val engine = object : Engine() {
            override fun searchSongs(query: String): List<com.raagmusic.app.data.youtube.Track> {
                if (query.contains("old category")) { started.set(true); Thread.sleep(2_000) }
                return super.searchSongs(query)
            }
        }
        withModel(engine) { vm ->
            val initialQueue = vm.playback.value.queueIds
            withContext(Dispatchers.Main) { vm.openBrowse("Old", "old category") }
            eventually { started.get() }
            withContext(Dispatchers.Main) { vm.openBrowse("Jazz", "jazz music") }
            eventually { vm.browse.value?.songs?.items?.isNotEmpty() == true && vm.browse.value?.releases?.loading == false }
            assertEquals("Jazz", vm.browse.value?.title)
            assertEquals("Jazz", vm.browse.value?.songs?.items?.first()?.genre)
            assertEquals(initialQueue, vm.playback.value.queueIds)
            assertTrue(vm.searchResults.value.isEmpty())
        }
    }

    @Test fun typingDebouncesDeduplicatesAndLeavingCancelsPendingSearch() = runBlocking {
        val engine = Engine()
        withModel(engine) { vm ->
            withContext(Dispatchers.Main) { vm.search("s"); vm.search("si"); vm.search("sig"); vm.search("signal") }
            eventually { !vm.searchLoading.value && vm.searchResults.value.firstOrNull()?.title == "signal" }
            assertEquals(1, engine.searches.count { it == "signal" })
            assertFalse(engine.searches.contains("sig"))
            eventually { vm.searchSuggestions.value == listOf("signal music") }
            withContext(Dispatchers.Main) { vm.search(" SIGNAL  ") }
            delay(600)
            assertEquals(1, engine.searches.count { it.equals("signal", true) })
            withContext(Dispatchers.Main) { vm.search("cancel-me"); vm.clearSearch() }
            delay(600)
            assertFalse(engine.searches.contains("cancel-me"))
            assertEquals("", vm.searchQuery.value)
            assertEquals("All", vm.searchKind.value)
            assertTrue(vm.searchResults.value.isEmpty())
            assertTrue(vm.searchSuggestions.value.isEmpty())
            assertFalse(vm.searchLoading.value)
        }
    }

    @Test fun leavingInterruptsAnInFlightRequestWithoutRestoringOldResults() = runBlocking {
        val started = AtomicBoolean(false)
        val interrupted = AtomicBoolean(false)
        val engine = object : Engine() {
            override fun searchSongs(query: String): List<com.raagmusic.app.data.youtube.Track> {
                if (query == "slow") {
                    started.set(true)
                    try { Thread.sleep(10_000) } catch (e: InterruptedException) { interrupted.set(true); throw e }
                }
                return super.searchSongs(query)
            }
        }
        withModel(engine) { vm ->
            withContext(Dispatchers.Main) { vm.search("slow") }
            eventually { started.get() }
            withContext(Dispatchers.Main) { vm.clearSearch() }
            eventually { interrupted.get() }
            delay(200)
            assertTrue(vm.searchResults.value.isEmpty())
            assertNull(vm.searchError.value)
            assertFalse(vm.searchLoading.value)
        }
    }

    @Test fun searchStartsOnlySelectedSeedAndKeepsPlaybackModesWhenCleared() = runBlocking {
        val audio = File(app.cacheDir, "search-order-test.wav")
        val count = 8000 * 2 * 30
        val bytes = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count)
        audio.writeBytes(bytes.array())
        val engine = object : Engine() {
            override fun searchSongs(query: String) = (1..3).map {
                com.raagmusic.app.data.youtube.Track("searchtest$it", "Result $it", "Artist", "", 30)
            }
        }
        try {
            withModel(engine) { vm ->
                withContext(Dispatchers.Main) { vm.search("ordered") }
                eventually { !vm.searchLoading.value && vm.searchResults.value.size == 3 }
                // Keep the seed local so this boundary check needs no provider audio.
                val seed = vm.searchResults.value.first().copy(uri = audio.toURI().toString(), source = Source.DIRECT)
                withContext(Dispatchers.Main) { vm.playSearchResult(seed) }
                eventually { vm.playback.value.queueIds == listOf(seed.id) && vm.playback.value.playing }
                eventually { vm.playback.value.recommendationChain }
                assertEquals("Result 1", vm.currentTrack.value?.title)
                assertFalse(vm.playback.value.shuffle)
                assertEquals(Player.REPEAT_MODE_OFF, vm.playback.value.repeat)
                withContext(Dispatchers.Main) { vm.player.shuffle(); vm.player.repeat(); vm.clearSearch() }
                eventually { vm.playback.value.shuffle && vm.playback.value.repeat == Player.REPEAT_MODE_ALL }
                assertEquals(listOf(seed.id), vm.playback.value.queueIds)
                assertEquals(seed.id, vm.currentTrack.value?.id)
                withContext(Dispatchers.Main) { vm.player.repeat() }
                eventually { vm.playback.value.repeat == Player.REPEAT_MODE_ONE }
                withContext(Dispatchers.Main) { vm.player.previous() }
                assertEquals(listOf(seed.id), vm.playback.value.queueIds)
                assertEquals("", vm.searchQuery.value)
            }
        } finally { audio.delete() }
    }
}
