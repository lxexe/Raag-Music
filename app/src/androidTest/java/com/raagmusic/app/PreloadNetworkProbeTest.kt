package com.raagmusic.app

import android.net.TrafficStats
import android.os.Process
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** UID traffic includes socket read-ahead, unlike a Media3 bytes-read counter. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PreloadNetworkProbeTest {
    @Test fun measureOnePreparedSourcesWireAndCacheBudget() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val id = "5NV6Rdv1a3I"
        val reference = YoutubeStreamResolver.mediaUri(id)
        val events = CopyOnWriteArrayList<PlaybackTrace.Event>()
        val resolver = YoutubeStreamResolver(app.youtube, mediaCache = app.media)
        var preloader: QueuePreloader? = null
        try {
            withContext(Dispatchers.IO) { app.media.streamingCache.keys.filter { it.startsWith("youtube-audio/$id/") }.forEach(app.media.streamingCache::removeResource) }
            resolver.setPreloading(reference.toString())
            resolver.warm(reference.toString()) // exclude provider/bootstrap bytes from the source measurement
            val before = TrafficStats.getUidRxBytes(Process.myUid())
            Assume.assumeTrue(before >= 0)
            PlaybackTrace.observer = { events.add(it) }
            val bounded = InstrumentationRegistry.getArguments().getString("boundedIntro") == "true"
            val metered = InstrumentationRegistry.getArguments().getString("metered") == "true"
            if (bounded) app.media.prefetch(androidx.media3.datasource.DataSpec(reference), metered, resolver,
                maxBytes = if (metered) 64 * 1024L else 128 * 1024L)
            withContext(Dispatchers.Main) {
                val factory = ProgressiveMediaSource.Factory(ResolvingDataSource.Factory(app.media.playbackFactory(resolver), resolver))
                    .setContinueLoadingCheckIntervalBytes(32 * 1024)
                preloader = QueuePreloader(app, factory, VisualizationRenderers(app)).also {
                    it.createMediaSource(MediaItem.Builder().setMediaId(id).setUri(reference).build())
                    it.preload(reference.toString())
                }
            }
            withTimeout(15_000) { while (events.none { it.stage == "preload_samples_ready" }) delay(20) }
            delay(2_000)
            val wireWhilePrepared = TrafficStats.getUidRxBytes(Process.myUid()) - before
            withContext(Dispatchers.Main) { preloader!!.release(); preloader = null }
            delay(300)
            val cached = withContext(Dispatchers.IO) { app.media.streamingCache.keys.filter { it.startsWith("youtube-audio/$id/") }
                .sumOf { app.media.streamingCache.getCachedBytes(it, 0, Long.MAX_VALUE) } }
            Log.i("RaagPreloadNetworkProbe", "RESULT boundedIntro=$bounded metered=$metered sourceBudgetMs=1500 cachedBytes=$cached wireRxBytes=$wireWhilePrepared wireRxAfterCloseBytes=${TrafficStats.getUidRxBytes(Process.myUid())-before}")
        } finally {
            withContext(Dispatchers.Main) { preloader?.release() }
            PlaybackTrace.observer = null
        }
        Unit
    }
}
