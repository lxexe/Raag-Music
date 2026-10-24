package com.raagmusic.app.playback

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.PreloadMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

/** One upcoming source with parsed tracks and 1.5 seconds of samples, reused by the real player. */
@UnstableApi
internal class QueuePreloader(context: Context, private val delegate: MediaSource.Factory,
    renderers: RenderersFactory, private val onReady: (String) -> Unit = {}) : MediaSource.Factory by delegate {
    private val thread = HandlerThread("GeyamPlayback", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    val looper get() = thread.looper
    private val controlHandler = Handler(looper)
    val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    val bandwidth = DefaultBandwidthMeter.getSingletonInstance(context)
    private val selector = DefaultTrackSelector(context).apply { init({}, bandwidth) }
    private val capabilities = DefaultRendererCapabilitiesList.Factory(renderers).createRendererCapabilitiesList()
    private val sources = LinkedHashMap<String, PreloadMediaSource>()
    private val owned = mutableSetOf<PreloadMediaSource>()
    @Volatile private var target: PreloadMediaSource? = null
    private val reported = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<PreloadMediaSource, Boolean>())
    private fun ready(source: PreloadMediaSource) {
        if (reported.add(source)) {
            PlaybackTrace.mark(source.mediaItem.mediaId, "preload_samples_ready")
            if (source === target) source.mediaItem.localConfiguration?.uri?.toString()?.let(onReady)
        }
    }
    fun isReady(reference: String): Boolean = sources[reference]?.let { it in reported } == true
    private val factory = PreloadMediaSource.Factory(delegate, object : PreloadMediaSource.PreloadControl {
        override fun onSourcePrepared(source: PreloadMediaSource): Boolean {
            PlaybackTrace.mark(source.mediaItem.mediaId, "preload_source_prepared")
            return source === target
        }
        override fun onTracksSelected(source: PreloadMediaSource): Boolean {
            PlaybackTrace.mark(source.mediaItem.mediaId, "preload_tracks_selected")
            return source === target
        }
        override fun onContinueLoadingRequested(source: PreloadMediaSource, bufferedPositionUs: Long): Boolean {
            if (bufferedPositionUs >= 1_500_000) ready(source)
            return source === target && bufferedPositionUs < 1_500_000
        }
        override fun onUsedByPlayer(source: PreloadMediaSource) {
            PlaybackTrace.mark(source.mediaItem.mediaId, if (source in reported) "preloaded_source_handoff" else "source_attached")
        }
        override fun onLoadedToTheEndOfSource(source: PreloadMediaSource) {
            ready(source)
        }
    }, selector, bandwidth, capabilities.rendererCapabilities, allocator, looper)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource = factory.createMediaSource(mediaItem).also { source ->
        mediaItem.localConfiguration?.uri?.toString()?.let { reference ->
            sources.put(reference, source)?.let { old ->
                old.releasePreloadMediaSource(); owned.remove(old); reported.remove(old)
            }
        }
    }

    fun prune(references: Set<String>) {
        sources.entries.removeAll { (reference, source) ->
            if (reference !in references) {
                source.releasePreloadMediaSource(); owned.remove(source); reported.remove(source); true
            } else false
        }
    }
    fun retain(references: Set<String>, current: String?) {
        prune(references)
        owned.removeAll { previous ->
            if (previous.mediaItem.localConfiguration?.uri?.toString() != current) {
                previous.releasePreloadMediaSource(); reported.remove(previous); true
            } else false
        }
        target = null
    }

    fun preload(reference: String) {
        val source = sources[reference] ?: return
        if (target === source) return
        target = source
        owned.add(source)
        PlaybackTrace.mark(source.mediaItem.mediaId, "preload_start")
        // A pending release clears the source's own handler. Post via our control handler so a
        // quality change can release then re-preload the same source without losing that request.
        controlHandler.post { if (target === source) source.preload(0) }
    }

    fun release() {
        target = null
        sources.values.forEach { it.releasePreloadMediaSource() }; sources.clear(); reported.clear(); owned.clear()
        controlHandler.post { selector.release(); capabilities.release(); thread.quitSafely() }
    }
}
