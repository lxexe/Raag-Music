package com.raagmusic.app.playback

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Opt-in stage tracing. Never records signed URLs, request bodies, cookies, or audio. */
object PlaybackTrace {
    data class Event(val id: String, val stage: String, val atMs: Long, val durationMs: Long = 0)
    @Volatile var observer: ((Event) -> Unit)? = null
    private val providerId = ThreadLocal<String>()
    fun mark(id: String, stage: String, durationMs: Long = 0) {
        observer?.invoke(Event(id, stage, System.nanoTime() / 1_000_000, durationMs))
    }
    fun <T> provider(id: String, block: () -> T): T {
        providerId.set(id)
        try { return block() } finally { providerId.remove() }
    }
    fun network(stage: String, elapsedMs: Long) { providerId.get()?.let { mark(it, stage, elapsedMs) } }
}

@UnstableApi
internal class PlaybackAnalytics : AnalyticsListener {
    private fun AnalyticsListener.EventTime.id(): String = if (windowIndex in 0 until timeline.windowCount)
        timeline.getWindow(windowIndex, Timeline.Window()).mediaItem.mediaId else ""
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        if (state == Player.STATE_READY) PlaybackTrace.mark(eventTime.id(), "ready")
    }
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) {
        PlaybackTrace.mark(eventTime.id(), "decoder_ready", initializationDurationMs)
    }
    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        // AudioTrack's playback head advanced. This is output evidence, not a microphone measurement.
        PlaybackTrace.mark(eventTime.id(), "audio_output")
    }
    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        PlaybackTrace.mark(eventTime.id(), when {
            decoderReuseEvaluation == null -> "initial_audio_format"
            decoderReuseEvaluation.result == androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO -> "decoder_reuse_no"
            else -> "decoder_reuse_yes"
        })
    }
    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
        PlaybackTrace.mark(eventTime.id(), "sink_initialized")
    }
    override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
        PlaybackTrace.mark(eventTime.id(), "sink_released")
    }
    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        PlaybackTrace.mark(eventTime.id(), "audio_underrun")
    }
}
