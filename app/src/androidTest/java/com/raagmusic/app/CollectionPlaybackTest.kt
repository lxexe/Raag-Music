package com.raagmusic.app

import android.content.ComponentName
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CollectionPlaybackTest {
    @Test fun continuationOutlivesTheScreenAndCannotAppendToAReplacementQueue() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val features = app.features.state.first()
        val library = app.library.state.first()
        val audio = File(app.cacheDir, "collection-audit.wav")
        val count = 8_000 * 2 * 30
        val bytes = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count)
        audio.writeBytes(bytes.array())
        val song = com.raagmusic.app.domain.Track("collection-local", "Local fixture", "Artist", "", audio.toURI().toString())
        val gate = CountDownLatch(1)
        val entered = AtomicBoolean(false)
        val next = com.raagmusic.app.data.youtube.Track("nextSong001", "Next", "Artist", "", 180)
        val source = object : ExtractionEngine {
            override fun collectionPage(url: String, cursor: CollectionCursor?): CollectionPage {
                entered.set(true); gate.await(6, TimeUnit.SECONDS); return CollectionPage(listOf(next))
            }
            override fun searchSongs(query: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
            override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
            override fun collectionTracks(url: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
            override fun audioStreamUrl(trackId: String) = error("No network audio in a queue fixture")
        }
        val repository = YoutubeMusicRepository(source)
        val collection = BrowseCollection("collection", "Fixture", "Artist", "", "https://www.youtube.com/playlist?list=fixture", 2, CollectionKind.PLAYLIST)
        val cursor = CollectionCursor(org.schabi.newpipe.extractor.Page("https://example.com/next"))
        val future = withContext(Dispatchers.Main) { MediaController.Builder(app,
            SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync() }
        var controller: MediaController? = null
        suspend fun eventually(check: suspend () -> Boolean) = withTimeout(10_000) { while (!check()) delay(25) }
        try {
            app.features.update { it.copy(preferences = it.preferences.copy(rememberQueue = false, collectHistory = false)) }
            controller = withContext(Dispatchers.IO) { future.get(8, TimeUnit.SECONDS) }
            withContext(Dispatchers.Main) {
                controller.setMediaItem(song.asMediaItem(collectionToken = "first")); controller.prepare(); controller.play()
            }
            eventually { withContext(Dispatchers.Main) { controller.isPlaying } }
            app.continueCollection(collection, cursor, setOf(song.id), "first", repository)
            eventually { entered.get() }
            assertEquals(1L, gate.count)
            assertTrue(withContext(Dispatchers.Main) { controller.isPlaying })
            gate.countDown()
            eventually { withContext(Dispatchers.Main) { controller.mediaItemCount == 2 } }
            withContext(Dispatchers.Main) { assertEquals(song.id, controller.currentMediaItem?.mediaId) }

            // A second request is held while a different selection replaces its queue.
            entered.set(false)
            val late = CountDownLatch(1)
            val slow = object : ExtractionEngine by source {
                override fun collectionPage(url: String, cursor: CollectionCursor?): CollectionPage {
                    entered.set(true); late.await(5, TimeUnit.SECONDS); return CollectionPage(listOf(next))
                }
            }
            withContext(Dispatchers.Main) { controller.setMediaItem(song.asMediaItem(collectionToken = "second")); controller.prepare(); controller.play() }
            app.continueCollection(collection, cursor, setOf(song.id), "second", YoutubeMusicRepository(slow))
            eventually { entered.get() }
            withContext(Dispatchers.Main) { controller.setMediaItem(song.copy(id = "replacement").asMediaItem()); controller.prepare(); controller.play() }
            eventually { withContext(Dispatchers.Main) { controller.currentMediaItem?.mediaId == "replacement" } }
            late.countDown(); delay(300)
            withContext(Dispatchers.Main) { assertEquals(1, controller.mediaItemCount) }
        } finally {
            gate.countDown(); app.cancelCollectionContinuation()
            withContext(Dispatchers.Main) { controller?.pause(); controller?.clearMediaItems(); MediaController.releaseFuture(future) }
            delay(300)
            app.features.update { features }; app.library.update { library }; audio.delete()
        }
    }
}
