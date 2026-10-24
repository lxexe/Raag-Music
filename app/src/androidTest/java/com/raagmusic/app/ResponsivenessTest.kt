package com.raagmusic.app

import android.os.StrictMode
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.FeatureState
import com.raagmusic.app.domain.ListeningPreferences
import com.raagmusic.app.playback.*
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ResponsivenessTest {
    private val engine = object : ExtractionEngine {
        override fun audioStreamUrl(trackId: String): String = error("No audio in this test")
        override fun searchSongs(query: String) = emptyList<Track>()
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<Track>()
    }
    @Test fun hiddenUiStopsPositionPollingAndResumesWithFreshState() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val original = app.features.state.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val events = CopyOnWriteArrayList<PlaybackTrace.Event>()
        var connection: PlayerConnection? = null
        try {
            app.features.update { FeatureState(preferences = ListeningPreferences(rememberQueue = false)) }
            PlaybackTrace.observer = { events.add(it) }
            withContext(Dispatchers.Main) { connection = PlayerConnection(app, scope) }
            withTimeout(5_000) { while (events.none { it.stage == "ui_position_poll" }) delay(10) }
            withContext(Dispatchers.Main) { connection!!.setUiVisible(false) }
            events.clear(); delay(1_600)
            assertEquals(0, events.count { it.stage == "ui_position_poll" })
            withContext(Dispatchers.Main) { connection!!.setUiVisible(true) }
            withTimeout(2_000) { while (events.none { it.stage == "ui_position_poll" }) delay(10) }
        } finally {
            withContext(Dispatchers.Main) { connection?.close() }
            scope.cancel(); PlaybackTrace.observer = null; app.features.update { original }
        }
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 28)
    fun repositoryConstructionAndCachedArtistReadsDoNoMainThreadDiskIo() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "strict-metadata-test").apply { mkdirs() }
        File(directory, "artist-metadata-v1.json").writeText("{}")
        val violations = CopyOnWriteArrayList<String>()
        try {
            withContext(Dispatchers.Main) {
                val previous = StrictMode.getThreadPolicy()
                StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .penaltyListener({ it.run() }) { violations.add(it.toString()) }.build())
                try { YoutubeMusicRepository(engine, metadataDirectory = directory).cachedArtistProfile("Singer") }
                finally { StrictMode.setThreadPolicy(previous) }
            }
            delay(100)
            assertTrue(violations.joinToString(), violations.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun emptyDownloadsDoNotPollTheDatabaseWhileThePlayerIsIdle() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val original = app.features.state.first()
        val store = ViewModelStore()
        val events = CopyOnWriteArrayList<PlaybackTrace.Event>()
        try {
            app.features.update { FeatureState(preferences = ListeningPreferences(rememberQueue = false)) }
            PlaybackTrace.observer = { events.add(it) }
            withContext(Dispatchers.Main) { store.put("idle", MusicViewModel(app, YoutubeMusicRepository(engine))) }
            withTimeout(5_000) { while (events.none { it.stage == "download_index_read" }) delay(10) }
            delay(200); events.clear()
            delay(5_500)
            assertEquals(0, events.count { it.stage == "download_index_read" })
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            PlaybackTrace.observer = null
            app.features.update { original }
        }
    }
    @Test fun sessionMetadataIsUsableBeforeLibraryPersistenceAndAlbumCompilationsStayGapless() {
        val item = MediaItem.Builder().setMediaId("firstSong01").setUri(YoutubeStreamResolver.mediaUri("firstSong01"))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Immediate title").setArtist("Artist one").setAlbumTitle("Continuous album")
                .setExtras(android.os.Bundle().apply { putString(COLLECTION_SESSION, "album:fixture") }).build()).build()
        val next = item.buildUpon().setMediaId("secondSong1").setMediaMetadata(item.mediaMetadata.buildUpon().setArtist("Artist two").build()).build()
        assertEquals("Immediate title", item.asTrack()!!.title)
        assertEquals("firstSong01", item.asTrack()!!.uri)
        assertTrue(preserveAlbumTransition(item, next, false))
        assertFalse(preserveAlbumTransition(item, next, true))
        assertFalse(preserveAlbumTransition(item, next.buildUpon().setMediaMetadata(next.mediaMetadata.buildUpon().setAlbumTitle("").build()).build(), false))
    }
}
