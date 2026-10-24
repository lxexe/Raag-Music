package com.raagmusic.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.LyricsRepository
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith

/** Optional live-provider checks; normal CI must not depend on third-party availability. */
@RunWith(AndroidJUnit4::class)
class ProviderSmokeTest {
    @Test fun musicAutocompleteReturnsMusicQueriesAndCachesTheResult() = runBlocking {
        val repository = YoutubeMusicRepository(initialCountry = "IN")
        val start = android.os.SystemClock.elapsedRealtime()
        val suggestions = repository.searchSuggestions("arijit")
        val remoteMs = android.os.SystemClock.elapsedRealtime() - start
        Assert.assertTrue(suggestions.any { it.contains("arijit", true) })
        val cachedAt = android.os.SystemClock.elapsedRealtime()
        Assert.assertEquals(suggestions, repository.searchSuggestions("arijit"))
        android.util.Log.i("GeyamAudit", "musicSuggestionsMs=$remoteMs cachedSuggestionsMs=${android.os.SystemClock.elapsedRealtime() - cachedAt} count=${suggestions.size}")
        Unit
    }
    @Before fun optIn() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
    }
    @Test fun musicSearchVideosAndContinuationReturnRealResults() = runBlocking {
        val repository = YoutubeMusicRepository(initialCountry = "IN")
        val songs = repository.searchPage("Arijit Singh", "Songs")
        Assert.assertTrue(songs.tracks.isNotEmpty())
        val next = songs.next ?: error("Expected more song search results")
        val second = repository.searchPage("Arijit Singh", "Songs", next)
        Assert.assertTrue(second.tracks.any { track -> songs.tracks.none { it.id == track.id } })
        val videos = repository.searchPage("Arijit Singh", "Videos")
        Assert.assertTrue(videos.tracks.isNotEmpty())
        Assert.assertTrue(videos.tracks.all { it.title.isNotBlank() && it.id.length == 11 })
        android.util.Log.i("RaagProviderSmoke", "Search pages: ${songs.tracks.size} + ${second.tracks.size} songs, ${videos.tracks.size} music videos")
        Unit
    }

    @Test fun individualSingersHaveDistinctRealPortraitsAndProviderCredits() = runBlocking {
        val repository = YoutubeMusicRepository(initialCountry = "IN")
        val arijit = repository.getArtistDetails("Arijit Singh") ?: error("Missing Arijit profile")
        val shreya = repository.getArtistDetails("Shreya Ghoshal") ?: error("Missing Shreya profile")
        Assert.assertTrue(ArtistNames.same(arijit.name, "Arijit Singh"))
        Assert.assertTrue(ArtistNames.same(shreya.name, "Shreya Ghoshal"))
        Assert.assertTrue(arijit.imageUrl.isNotBlank() && shreya.imageUrl.isNotBlank())
        Assert.assertNotEquals(arijit.imageUrl, shreya.imageUrl)
        Assert.assertNotEquals(arijit.channelUrl, shreya.channelUrl)
        Assert.assertNotNull(arijit.subscribers)
        Assert.assertTrue(arijit.subscribers!! >= 1_000_000)
        Assert.assertNotNull(shreya.subscribers)
        Assert.assertTrue(shreya.subscribers!! > 0)
        val song = repository.searchTracks("Ve Kamleya").first().first()
        val credits = repository.getSongArtists(song.id)
        val relatedArtists = repository.getRelatedArtistNames(song.id)
        Assert.assertTrue(relatedArtists.isNotEmpty())
        android.util.Log.i("RaagProviderSmoke", "Individual artists: ${arijit.name}=${arijit.imageUrl}; ${shreya.name}=${shreya.imageUrl}; channels=${arijit.channelUrl},${shreya.channelUrl}; subscribers=${arijit.subscribers},${shreya.subscribers}; monthly=${arijit.monthlyAudience},${shreya.monthlyAudience}; song=${song.title}; credits=$credits; related artists=${relatedArtists.take(6)}")
        Assert.assertTrue(credits.any { ArtistNames.same(it, "Arijit Singh") })
        Assert.assertTrue(credits.any { ArtistNames.same(it, "Shreya Ghoshal") })
        Unit
    }
    @Test fun artistProfileUsesPublicArtistMetadata() = runBlocking {
        val profile = YoutubeMusicRepository().getArtistProfile("5NV6Rdv1a3I") ?: error("Missing artist profile")
        android.util.Log.i("RaagProviderSmoke", "Artist metadata: ${profile.name}; subscribers=${profile.subscribers}; monthly=${profile.monthlyAudience}")
        Assert.assertTrue(profile.name.replace(" ", "").contains("DaftPunk", ignoreCase = true))
        Assert.assertTrue(profile.imageUrl.isNotBlank() || profile.bannerUrl.isNotBlank())
        Assert.assertTrue(profile.channelUrl.startsWith("https://"))
        Assert.assertTrue(profile.subscribers == null || profile.subscribers >= 0)
        Unit
    }
    @Test fun sharedSongAndAlbumMetadataAndRelatedArtistsAreReal() = runBlocking {
        val repository = YoutubeMusicRepository()
        val song = repository.getSong("5NV6Rdv1a3I")
        Assert.assertEquals("5NV6Rdv1a3I", song.id)
        Assert.assertTrue(song.title.isNotBlank() && song.artist.isNotBlank() && song.durationSeconds > 0)
        val related = repository.getRelatedSongs(song.id)
        Assert.assertTrue(related.isNotEmpty())
        val albums = repository.searchCollections("Daft Punk Random Access Memories", CollectionKind.ALBUM).first()
        Assert.assertTrue(albums.isNotEmpty())
        val album = repository.getCollection(albums.first().url, CollectionKind.ALBUM)
        Assert.assertTrue(album.title.isNotBlank())
        Assert.assertTrue(repository.getCollectionPage(album).tracks.isNotEmpty())
        android.util.Log.i("RaagProviderSmoke", "Link metadata: ${song.title}; album=${album.title}; related=${related.size}")
        Unit
    }
    @Test fun lyricsFetchReturnsRealTimedOrPlainLyrics() = runBlocking {
        val track = com.raagmusic.app.domain.Track("5NV6Rdv1a3I", "Get Lucky", "Daft Punk", "", "5NV6Rdv1a3I", Source.YOUTUBE, durationSeconds = 369)
        val lyrics = LyricsRepository().get(track)
        Assert.assertTrue(lyrics.lines.isNotEmpty() || lyrics.plain.isNotBlank())
        Assert.assertEquals(lyrics.lines.sortedBy { it.timeMs }, lyrics.lines)
        android.util.Log.i("RaagProviderSmoke", "Lyrics loaded: ${lyrics.lines.size} timed lines; plain=${lyrics.plain.isNotBlank()}")
        Unit
    }
    @Test fun playlistSearchHandlesProviderSchemaVariants() = runBlocking {
        val collections = YoutubeMusicRepository().searchCollections("1000 songs", CollectionKind.PLAYLIST).first()
        Assert.assertTrue(collections.isNotEmpty())
        Assert.assertTrue(collections.all { it.url.startsWith("https://") && it.kind == CollectionKind.PLAYLIST })
        Unit
    }
    @Test fun collectionContinuationReturnsAnotherRealPage() = runBlocking {
        val repository = YoutubeMusicRepository()
        // Use a canonical public playlist so the paging check is independent of
        // search ranking and Music search's sometimes absent track-count hints.
        val collection = BrowseCollection("PLRBp0Fe2GpgnIh0AiYKh7o7HnYAej-5ph", "NCS releases", "NCS", "",
            "https://www.youtube.com/playlist?list=PLRBp0Fe2GpgnIh0AiYKh7o7HnYAej-5ph", 0, CollectionKind.PLAYLIST)
        val first = repository.getCollectionPage(collection)
        Assert.assertTrue(first.tracks.isNotEmpty())
        val next = first.next ?: error("Expected a continuation cursor for a large playlist")
        val second = repository.getCollectionPage(collection, next)
        Assert.assertTrue(second.tracks.isNotEmpty())
        Assert.assertTrue(second.tracks.any { song -> first.tracks.none { it.id == song.id } })
        android.util.Log.i("RaagProviderSmoke", "Collection pages loaded: ${first.tracks.size} + ${second.tracks.size} tracks")
        Unit
    }
}
