package com.raagmusic.app

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
@RunWith(AndroidJUnit4::class)
class QueuePreloadTest {
    @Test fun parsedSamplesSurviveHandoffAndRapidSkipsCancelUnrelatedResolution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "preload-tone.wav")
        val count = 8_000 * 2 * 8
        val buffer = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count)
        repeat(count / 2) { buffer.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 8000) * 2000).toInt().toShort()) }
        file.writeBytes(buffer.array())
        val entered = AtomicBoolean(); val cancelled = AtomicBoolean()
        val events = CopyOnWriteArrayList<PlaybackTrace.Event>()
        val engine = object : ExtractionEngine {
            override fun audioStream(trackId: String, maxBitrate: Int): ResolvedAudioStream {
                if (trackId == "slowSong001") {
                    entered.set(true)
                    try { Thread.sleep(10_000) } catch (e: InterruptedException) { cancelled.set(true); throw e }
                }
                return ResolvedAudioStream(file.toUri().toString(), formatIdentity = trackId)
            }
            override fun audioStreamUrl(trackId: String) = error("Unexpected")
            override fun searchSongs(query: String) = emptyList<Track>()
            override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
            override fun collectionTracks(url: String) = emptyList<Track>()
        }
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine))
        var player: ExoPlayer? = null
        var preloader: QueuePreloader? = null
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri(YoutubeStreamResolver.mediaUri(id)).build()
        val queue = listOf("firstSong01", "secondSong1", "slowSong001", "finalSong01").map(::item)
        suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) { while (!check()) delay(10) }
        try {
            PlaybackTrace.observer = { events.add(it) }
            withContext(Dispatchers.Main) {
                val renderers = VisualizationRenderers(context)
                val factory = ProgressiveMediaSource.Factory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context), resolver))
                    .setContinueLoadingCheckIntervalBytes(16 * 1024)
                val preload = QueuePreloader(context, factory, renderers).also { preloader = it }
                player = ExoPlayer.Builder(context, renderers).setMediaSourceFactory(preload)
                    .setPlaybackLooper(preload.looper).setBandwidthMeter(preload.bandwidth)
                    .setLoadControl(DefaultLoadControl.Builder().setAllocator(preload.allocator)
                        .setBufferDurationsMs(3_000, 5_000, 250, 500).build()).build().apply {
                        setMediaItems(queue); prepare(); play()
                    }
            }
            eventually { withContext(Dispatchers.Main) { player!!.isPlaying } }
            withContext(Dispatchers.Main) {
                resolver.setPreloading(queue[1].localConfiguration!!.uri.toString())
                preloader!!.preload(queue[1].localConfiguration!!.uri.toString())
            }
            eventually { events.any { it.id == "secondSong1" && it.stage == "preload_samples_ready" } }
            // Quality changes discard speculative samples. Reusing the same source must not
            // lose the new preload when its old release clears pending source callbacks.
            withContext(Dispatchers.Main) {
                preloader!!.retain(queue.map { it.localConfiguration!!.uri.toString() }.toSet(), queue[0].localConfiguration!!.uri.toString())
                preloader!!.preload(queue[1].localConfiguration!!.uri.toString())
            }
            eventually { events.count { it.id == "secondSong1" && it.stage == "preload_samples_ready" } >= 2 }
            val resolves = events.count { it.id == "secondSong1" && it.stage == "resolve_start" }
            withContext(Dispatchers.Main) { player!!.seekToNextMediaItem() }
            eventually { withContext(Dispatchers.Main) { player!!.currentMediaItemIndex == 1 && player!!.isPlaying } }
            assertEquals(resolves, events.count { it.id == "secondSong1" && it.stage == "resolve_start" })
            withContext(Dispatchers.Main) {
                preloader!!.retain(queue.map { it.localConfiguration!!.uri.toString() }.toSet(), queue[1].localConfiguration!!.uri.toString())
                resolver.setPreloading(queue[2].localConfiguration!!.uri.toString())
                preloader!!.preload(queue[2].localConfiguration!!.uri.toString())
            }
            eventually { entered.get() }
            val at = System.nanoTime()
            withContext(Dispatchers.Main) { player!!.seekToNextMediaItem(); player!!.seekToNextMediaItem() }
            eventually { withContext(Dispatchers.Main) {
                player!!.playerError?.let { throw it }
                player!!.currentMediaItemIndex == 3 && player!!.playbackState == Player.STATE_READY && player!!.isPlaying
            } }
            assertTrue(cancelled.get())
            assertTrue("Latest selection waited behind cancelled work", (System.nanoTime() - at) / 1_000_000 < 2_000)
        } finally {
            withContext(Dispatchers.Main) { player?.release(); preloader?.release() }
            PlaybackTrace.observer = null; file.delete()
        }
    }
}
