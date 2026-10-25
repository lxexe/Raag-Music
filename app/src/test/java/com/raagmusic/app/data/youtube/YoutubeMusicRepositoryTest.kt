package com.raagmusic.app.data.youtube

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class YoutubeMusicRepositoryTest {
    @Test fun verificationDoesNotTriggerTheRepositoryNetworkRetry() = runBlocking {
        var calls = 0
        val engine = object : FakeEngine() {
            override fun audioStreamUrl(trackId: String): String { calls++; throw YoutubeVerificationRequiredException() }
            override fun searchSongs(query: String) = emptyList<Track>()
        }
        try {
            YoutubeMusicRepository(engine).getAudioStreamUrl("5NV6Rdv1a3I")
            fail("Expected verification error")
        } catch (_: YoutubeVerificationRequiredException) { }
        assertEquals(1, calls)
    }

    @Test fun rateLimitingDoesNotTriggerRepositoryRetry() = runBlocking {
        var calls = 0
        val engine = object : FakeEngine() {
            override fun audioStreamUrl(trackId: String): String { calls++; throw YoutubeRateLimitedException(120_000) }
            override fun searchSongs(query: String) = emptyList<Track>()
        }
        try { YoutubeMusicRepository(engine).getAudioStreamUrl("5NV6Rdv1a3I"); fail("Expected rate limit") }
        catch (error: YoutubeRateLimitedException) { assertEquals(120_000L, error.retryAfterMillis) }
        assertEquals(1, calls)
    }
    @Test fun allSearchHasAnIndependentCacheFromSongSearch() = runBlocking {
        val engine = Engine(listOf(song))
        val repository = YoutubeMusicRepository(engine)
        repository.searchPage("song", "All", foreground = true)
        repository.searchPage("SONG", "Songs", foreground = true)
        assertEquals(listOf(song), repository.searchTracks("song").first())
        assertEquals(2, engine.searchCalls)
        assertNotNull(repository.cachedSearchPage("song", "All"))
        repository.searchPage("song", "Songs", refresh = true)
        assertEquals(3, engine.searchCalls)
    }
    @Test fun pagedSearchKeepsFilterAndAllResultsWithoutDuplicateRows() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val tracks = (0 until 32).map { Track("result%05d".format(it), "Song $it", "Artist", "", 30) }
        val engine = object : FakeEngine() {
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage {
                calls += query to kind
                return SearchPage(tracks = tracks + tracks.first())
            }
            override fun searchSongs(query: String) = error("Use the filtered search API")
            override fun audioStreamUrl(trackId: String) = error("unused")
        }
        val repository = YoutubeMusicRepository(engine)
        assertEquals(tracks, repository.searchPage("  hello  ", "Videos").tracks)
        assertEquals(tracks, repository.searchPage("hello", "Songs").tracks)
        assertEquals(tracks, repository.searchPage(" HELLO ", "Videos").tracks)
        assertEquals(listOf("hello" to "Videos", "hello" to "Songs"), calls)
        repository.setCountry("IN")
        assertEquals(tracks, repository.searchPage("hello", "Videos").tracks)
        assertEquals(3, calls.size)
    }

    @Test fun pagedSearchPreservesDistinctArtistsAndCollectionTypes() = runBlocking {
        val artist = ArtistProfile("A", channelUrl = "channel-a")
        val other = ArtistProfile("B", channelUrl = "channel-b")
        val album = BrowseCollection("same-id", "Album", "A", "", "https://example.com/album", 2, CollectionKind.ALBUM)
        val playlist = album.copy(kind = CollectionKind.PLAYLIST)
        val engine = object : FakeEngine() {
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?) =
                SearchPage(artists = listOf(artist, other, artist), collections = listOf(album, playlist, album))
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = error("unused")
        }
        val page = YoutubeMusicRepository(engine).searchPage("artist", "All")
        assertEquals(listOf(artist, other), page.artists)
        assertEquals(listOf(album, playlist), page.collections)
    }

    @Test fun singerCreditsAndRelatedArtistsShareOneCachedRequest() = runBlocking {
        var calls = 0
        val engine = object : FakeEngine() {
            override fun songArtistCredits(trackId: String): SongArtistCredits {
                calls++
                return SongArtistCredits(listOf("Arijit Singh", "Shreya Ghoshal"), listOf("Sunidhi Chauhan", "Pritam"))
            }
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = error("unused")
        }
        val repository = YoutubeMusicRepository(engine)
        assertEquals(listOf("Arijit Singh", "Shreya Ghoshal"), repository.getSongArtists("abcdefghijk"))
        assertEquals(listOf("Sunidhi Chauhan", "Pritam"), repository.getRelatedArtistNames("abcdefghijk"))
        assertEquals(1, calls)
        repository.setCountry("IN")
        repository.getRelatedArtistNames("abcdefghijk")
        assertEquals(2, calls)
    }

    @Test fun recommendationsKeepEverySuggestionAndShareOneMetadataLookup() = runBlocking {
        var calls = 0
        val all = (0 until 25).map { Track("suggest%04d".format(it), "Song $it", "Artist", "", 30) }
        val engine = object : FakeEngine() {
            override fun relatedSongs(trackId: String): List<Track> { calls++; return all + all.first() }
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = error("unused")
        }
        val repository = YoutubeMusicRepository(engine)
        assertEquals(all, repository.getRelatedSongs("abcdefghijk"))
        assertEquals(all, repository.getRelatedSongs("abcdefghijk"))
        assertEquals(1, calls)
        repository.setCountry("IN")
        repository.getRelatedSongs("abcdefghijk")
        assertEquals(2, calls)
    }
    @Test fun artistLookupIsKeyedByIndividualNameAndNeverUsesSongUploader() = runBlocking {
        val names = mutableListOf<String>()
        val engine = object : FakeEngine() {
            override fun artistProfileByName(name: String): ArtistProfile {
                names += name
                return ArtistProfile(name, imageUrl = "$name-portrait", channelUrl = "$name-channel")
            }
            override fun artistProfile(trackId: String): ArtistProfile = error("Must not use uploader")
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = error("unused")
        }
        val repository = YoutubeMusicRepository(engine)
        val arijit = repository.getArtistProfileByName("Arijit Singh")!!
        val shreya = repository.getArtistProfileByName("Shreya Ghoshal")!!
        assertNotEquals(arijit.imageUrl, shreya.imageUrl)
        assertNotEquals(arijit.channelUrl, shreya.channelUrl)
        assertEquals(arijit, repository.getArtistProfileByName(" arijit singh "))
        assertEquals(listOf("Arijit Singh", "Shreya Ghoshal"), names)
    }

    @Test fun suggestionsAreBoundedCachedAndResetForCountryChanges() = runBlocking {
        var calls = 0
        var querySeen = ""
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = error("Not used")
            override fun searchSuggestions(query: String): List<String> {
                calls++; querySeen = query
                return listOf(" Daft Punk ", "daft punk", "") + (1..10).map { "Song $it" }
            }
        }
        val repository = YoutubeMusicRepository(engine)
        assertTrue(repository.searchSuggestions("d").isEmpty())
        assertEquals(0, calls)
        val results = repository.searchSuggestions("  daft  ")
        assertEquals("daft", querySeen)
        assertEquals(6, results.size)
        assertEquals("Daft Punk", results.first())
        assertEquals(results, repository.searchSuggestions("DAFT"))
        assertEquals(1, calls)
        repository.setCountry("IN")
        repository.searchSuggestions("daft")
        assertEquals(2, calls)
        repository.clearMetadataCache()
        repository.searchSuggestions("daft")
        assertEquals(3, calls)
    }

    @Test fun countryChangesReachTheProviderAndInvalidateRegionalMetadataOnly() = runBlocking {
        var region = ""
        val searches = mutableListOf<String>()
        val collections = mutableListOf<String>()
        var audioCalls = 0
        val engine = object : FakeEngine() {
            override fun setCountry(code: String) { region = code }
            override fun searchSongs(query: String): List<Track> { searches += region; return listOf(song) }
            override fun searchCollections(query: String, kind: CollectionKind): List<BrowseCollection> {
                collections += region
                return listOf(BrowseCollection("p", "Chart", "Artist", "", "https://www.youtube.com/playlist?list=p", 1, kind))
            }
            override fun audioStreamUrl(trackId: String): String { audioCalls++; return "https://media.example.com/$audioCalls" }
        }
        val repository = YoutubeMusicRepository(engine, initialCountry = "IN")
        repository.searchTracks("hits").first(); repository.searchCollections("hits", CollectionKind.PLAYLIST).first()
        repository.searchTracks("hits").first()
        repository.setCountry("IN"); repository.searchTracks("hits").first()
        assertEquals(listOf("IN"), searches)
        repository.setCountry("JP")
        repository.searchTracks("hits").first(); repository.searchCollections("hits", CollectionKind.PLAYLIST).first()
        assertEquals(listOf("IN", "JP"), searches)
        assertEquals(listOf("IN", "JP"), collections)
        assertNotEquals(repository.getAudioStreamUrl(song.id), repository.getAudioStreamUrl(song.id))
        try { repository.setCountry("ZZ"); fail("Expected invalid country") } catch (_: IllegalArgumentException) { }
        assertEquals("JP", region)
    }
    @Test fun foregroundSearchAndAutocompleteBypassBusyDiscovery() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String): List<Track> {
                started.countDown(); release.await(5, TimeUnit.SECONDS); return emptyList()
            }
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage =
                if (query == "home") super.searchPage(query, kind, cursor) else SearchPage(tracks = listOf(song))
            override fun searchSuggestions(query: String) = listOf("fast suggestion")
            override fun audioStreamUrl(trackId: String) = error("unused")
        }
        val repository = YoutubeMusicRepository(engine)
        val background = launch(Dispatchers.Default) { repository.searchTracks("home").first() }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(1_000) {
                assertEquals(listOf(song), repository.searchPage("song", "Songs", foreground = true).tracks)
                assertEquals(listOf("fast suggestion"), repository.searchSuggestions("song"))
            }
        } finally { release.countDown(); background.join() }
    }
    @Test fun selectedArtistProfileOpensItsCanonicalChannelWithoutSearchingAgain() = runBlocking {
        val profile = ArtistProfile("Chosen channel", channelUrl = "https://www.youtube.com/channel/selected")
        val engine = object : FakeEngine() {
            override fun artistProfileByName(name: String): ArtistProfile? = error("Don't search for a selected channel again")
            override fun artistDetails(profile: ArtistProfile) = profile.copy(description = "Channel details")
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = error("unused")
        }
        assertEquals(profile.channelUrl, YoutubeMusicRepository(engine).getArtistDetails(profile).channelUrl)
    }
    private val song = Track("abcdefghijk", "Song", "Artist", "https://example.com/art.jpg", 183)
    private abstract class FakeEngine : ExtractionEngine {
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<Track>()
    }
    private class Engine(val songs: List<Track> = emptyList(), val collections: List<BrowseCollection> = emptyList()) : FakeEngine() {
        val collectionQueries = mutableListOf<Pair<String, CollectionKind>>()
        override fun searchCollections(query: String, kind: CollectionKind): List<BrowseCollection> {
            collectionQueries += query to kind
            return collections
        }
        override fun collectionTracks(url: String) = songs
        var searchCalls = 0
        var streamCalls = 0
        var lastQuery = ""
        var searchThread = ""
        override fun searchSongs(query: String): List<Track> {
            searchCalls++; lastQuery = query; searchThread = Thread.currentThread().name
            return songs
        }
        override fun audioStreamUrl(trackId: String): String = "https://media.example.com/$trackId?generation=${++streamCalls}"
    }

    @Test fun searchIsColdTrimsInputAndDeduplicatesSongs() = runBlocking {
        val engine = Engine(listOf(song, song))
        val repository = YoutubeMusicRepository(engine)
        val result = repository.searchTracks("  song  ")
        assertEquals(0, engine.searchCalls)
        assertEquals(listOf(song), result.first())
        assertEquals("song", engine.lastQuery)
        result.first()
        assertEquals(1, engine.searchCalls)
    }

    @Test fun explicitRefreshDropsOnlyMetadataAndPerPlayExtractionRemainsFresh() = runBlocking {
        val engine = Engine(listOf(song))
        val repository = YoutubeMusicRepository(engine)
        repository.searchTracks("song").first()
        repository.clearMetadataCache()
        repository.searchTracks("song").first()
        assertEquals(2, engine.searchCalls)
        assertNotEquals(repository.getAudioStreamUrl(song.id), repository.getAudioStreamUrl(song.id))
    }

    @Test fun blankSearchDoesNotContactYoutube() = runBlocking {
        val engine = Engine()
        assertTrue(YoutubeMusicRepository(engine).searchTracks("   ").first().isEmpty())
        assertEquals(0, engine.searchCalls)
    }

    @Test fun streamUrlsAreExtractedAgainOnEveryPlay() = runBlocking {
        val engine = Engine()
        val repository = YoutubeMusicRepository(engine)
        assertNotEquals(repository.getAudioStreamUrl(song.id), repository.getAudioStreamUrl(song.id))
        assertEquals(2, engine.streamCalls)
    }

    @Test fun invalidTrackIdsNeverReachTheExtractor() = runBlocking {
        val engine = Engine()
        try { YoutubeMusicRepository(engine).getAudioStreamUrl("https://youtube.com/watch?v=abcdefghijk"); fail("Expected invalid ID") }
        catch (_: IllegalArgumentException) { assertEquals(0, engine.streamCalls) }
    }

    @Test fun blockingExtractionRunsOnTheInjectedIoDispatcher() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "extractor-test-io") }.asCoroutineDispatcher().use { io ->
            val engine = Engine(listOf(song))
            YoutubeMusicRepository(engine, io).searchTracks("song").first()
            // Coroutine debug mode can append the coroutine name to the worker's name.
            assertTrue(engine.searchThread.startsWith("extractor-test-io"))
        }
    }

    @Test fun cancelledSearchInterruptsBlockingExtraction() = runBlocking {
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String): List<Track> {
                started.countDown()
                try { Thread.sleep(10_000) }
                catch (e: InterruptedException) { interrupted.set(true); throw e }
                return emptyList()
            }
            override fun audioStreamUrl(trackId: String) = error("Not used")
        }
        val job = launch(Dispatchers.Default) { YoutubeMusicRepository(engine).searchTracks("song").first() }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(interrupted.get())
    }

    @Test fun collectionSearchDeduplicatesAndSeparatesAlbumAndPlaylistCaches() = runBlocking {
        val item = BrowseCollection("playlist-1", "Collection", "Artist", "", "https://www.youtube.com/playlist?list=playlist-1", 10, CollectionKind.PLAYLIST)
        val engine = Engine(collections = listOf(item, item))
        val repository = YoutubeMusicRepository(engine)
        assertEquals(listOf(item), repository.searchCollections(" Artist ", CollectionKind.PLAYLIST).first())
        repository.searchCollections("artist", CollectionKind.PLAYLIST).first()
        repository.searchCollections("Artist", CollectionKind.ALBUM).first()
        assertEquals(listOf("Artist" to CollectionKind.PLAYLIST, "Artist" to CollectionKind.ALBUM), engine.collectionQueries)
    }

    @Test fun collectionTracksPreserveOrderAndExcludeDuplicates() = runBlocking {
        val item = BrowseCollection("playlist-1", "Collection", "Artist", "", "https://www.youtube.com/playlist?list=playlist-1", 10, CollectionKind.PLAYLIST)
        val second = song.copy(id = "zyxwvutsrqp")
        assertEquals(listOf(song, second), YoutubeMusicRepository(Engine(listOf(song, second, song))).getCollectionTracks(item))
    }

    @Test fun persistentUiMappingContainsTheIdInsteadOfAPlaybackUrl() {
        val stored = song.toLibraryTrack()
        assertEquals(song.id, stored.uri)
        assertEquals(song.durationSeconds, stored.durationSeconds)
        assertTrue(stored.canDownload)
    }

    @Test fun continuationPagesAreForwardedAndEachPageIsDeduplicated() = runBlocking {
        val cursor = CollectionCursor(org.schabi.newpipe.extractor.Page("https://www.youtube.com/continuation"))
        var seen: CollectionCursor? = null
        val second = song.copy(id = "zyxwvutsrqp")
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = error("unused")
            override fun collectionPage(url: String, cursor: CollectionCursor?): CollectionPage {
                seen = cursor
                return if (cursor == null) CollectionPage(listOf(song, song)) else CollectionPage(listOf(second, second))
            }
        }
        val item = BrowseCollection("p", "Collection", "Artist", "", "https://www.youtube.com/playlist?list=p", 2, CollectionKind.PLAYLIST)
        val repository = YoutubeMusicRepository(engine)
        assertEquals(listOf(song), repository.getCollectionPage(item).tracks)
        assertEquals(listOf(second), repository.getCollectionPage(item, cursor).tracks)
        assertSame(cursor, seen)
    }
    @Test fun bitrateLimitReachesTheExtractor() = runBlocking {
        var receivedLimit = -1
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String) = "https://example.com/audio"
            override fun audioStreamUrl(trackId: String, maxBitrate: Int): String { receivedLimit = maxBitrate; return audioStreamUrl(trackId) }
        }
        YoutubeMusicRepository(engine).getAudioStream(song.id, 64_000)
        assertEquals(64_000, receivedLimit)
    }
    @Test fun failedProviderOperationRetriesOnceAndSuccessfulResultIsCached() = runBlocking {
        var calls = 0
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String): List<Track> {
                if (++calls == 1) throw java.io.IOException("temporary")
                return listOf(song)
            }
            override fun audioStreamUrl(trackId: String) = "https://example.com/audio"
        }
        val repository = YoutubeMusicRepository(engine)
        assertEquals(listOf(song), repository.searchTracks("song").first())
        assertEquals(listOf(song), repository.searchTracks("song").first())
        assertEquals(2, calls)
    }

    @Test fun unavailableTracksDoNotRepeatTheSameProviderRequest() = runBlocking {
        var calls = 0
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun audioStreamUrl(trackId: String): String {
                calls++
                throw YoutubeExtractionException("Region restricted",
                    org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("Region restricted"))
            }
        }
        try { YoutubeMusicRepository(engine).getAudioStreamUrl(song.id); fail("Expected restriction") }
        catch (_: YoutubeExtractionException) { }
        assertEquals(1, calls)
    }

    @Test fun audioAndCachedSearchDoNotWaitForBlockedDiscovery() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = object : FakeEngine() {
            override fun searchSongs(query: String): List<Track> {
                if (query == "slow") { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                return listOf(song)
            }
            override fun audioStreamUrl(trackId: String) = "https://example.com/audio"
        }
        val repository = YoutubeMusicRepository(engine)
        repository.searchTracks("cached").first()
        val slow = launch(Dispatchers.Default) { repository.searchTracks("slow").first() }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            withTimeout(1_000) {
                assertEquals(listOf(song), repository.searchTracks("cached").first())
                assertEquals("https://example.com/audio", repository.getAudioStreamUrl(song.id))
            }
        } finally { release.countDown(); slow.cancelAndJoin() }
    }

}
