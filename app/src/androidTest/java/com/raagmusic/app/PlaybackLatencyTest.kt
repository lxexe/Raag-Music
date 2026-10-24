package com.raagmusic.app

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.YoutubeMusicRepository
import com.raagmusic.app.playback.YoutubeStreamResolver
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

/** Real-provider diagnostic, opt-in so network availability never controls normal CI. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackLatencyTest {
    @Before fun optIn() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
    }

    @Test fun realAudioPreparationPrefetchAndDecoderReadiness() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as RaagApplication
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository())
        val id = "5NV6Rdv1a3I"
        val reference = YoutubeStreamResolver.mediaUri(id, "latency-${System.nanoTime()}")
        var player: ExoPlayer? = null
        try {
            val extractedAt = SystemClock.elapsedRealtime()
            val spec = resolver.warm(reference.toString())!!
            val extractionMs = SystemClock.elapsedRealtime() - extractedAt
            val replayAt = SystemClock.elapsedRealtime()
            val replay = resolver.warm(YoutubeStreamResolver.mediaUri(id, "replay-${System.nanoTime()}").toString())!!
            val replayResolveMs = SystemClock.elapsedRealtime() - replayAt
            Assert.assertEquals(spec.uri, replay.uri)
            Assert.assertEquals(spec.key, replay.key)
            val prefetchedAt = SystemClock.elapsedRealtime()
            app.media.prefetch(spec, metered = false)
            val prefetchMs = SystemClock.elapsedRealtime() - prefetchedAt
            Assert.assertTrue(app.media.streamingCache.isCached(spec.key!!, 0, 32 * 1024L))
            val cachedAt = SystemClock.elapsedRealtime()
            val source = app.media.playbackFactory.createDataSource()
            try {
                source.open(spec.buildUpon().setLength(32 * 1024L).build())
                val bytes = ByteArray(32 * 1024)
                var offset = 0
                while (offset < bytes.size) {
                    val count = source.read(bytes, offset, bytes.size - offset)
                    Assert.assertTrue(count > 0)
                    offset += count
                }
            } finally { source.close() }
            val cachedReadMs = SystemClock.elapsedRealtime() - cachedAt
            val readyAt = SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) {
                player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
                    ResolvingDataSource.Factory(app.media.playbackFactory, resolver)))
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 250, 750).build())
                    .build().apply { setMediaItem(MediaItem.Builder().setMediaId(id).setUri(reference).build()); prepare() }
            }
            withTimeout(30_000) {
                while (!withContext(Dispatchers.Main) {
                    player!!.playerError?.let { throw it }
                    player!!.playbackState == Player.STATE_READY
                }) delay(25)
            }
            val readyMs = SystemClock.elapsedRealtime() - readyAt
            withContext(Dispatchers.Main) {
                val formats = player!!.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                    .flatMap { group -> (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it) } }
                Assert.assertTrue(formats.isNotEmpty())
                val format = formats.first()
                android.util.Log.i("RaagPlaybackLatency", "video=$id extractionMs=$extractionMs replayResolveMs=$replayResolveMs prefetchMs=$prefetchMs cached32KiBReadMs=$cachedReadMs preparedToReadyMs=$readyMs codec=${format.sampleMimeType} sampleRate=${format.sampleRate}")
            }
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            resolver.invalidate()
        }
        Unit
    }
}
