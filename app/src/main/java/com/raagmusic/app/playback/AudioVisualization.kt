package com.raagmusic.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Samples Raag's decoded PCM only; never accesses the microphone or writes audio to disk. */
object AudioVisualization {
    val consumers = AtomicInteger(0)
    private val mutable = MutableStateFlow(List(24) { 0f })
    val levels = mutable.asStateFlow()
    internal fun publish(levels: List<Float>) { mutable.value = levels }
}

@UnstableApi
class VisualizationRenderers(context: Context, private val tracePcm: ((String) -> Unit)? = null) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                private var encoding = C.ENCODING_INVALID
                private var lastSample = 0L
                private var firstPcm = false
                private var firstSignal = false
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) { this.encoding = encoding; firstPcm = false; firstSignal = false; AudioVisualization.publish(List(24) { 0f }) }
                override fun handleBuffer(buffer: ByteBuffer) {
                    if (tracePcm != null && PlaybackTrace.observer != null && buffer.hasRemaining()) {
                        if (!firstPcm) { firstPcm = true; tracePcm.invoke("pcm_first") }
                        if (!firstSignal && encoding == C.ENCODING_PCM_16BIT) {
                            val pcm = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
                            while (pcm.remaining() >= 2) if (kotlin.math.abs(pcm.short.toInt()) > 32) {
                                firstSignal = true; tracePcm.invoke("pcm_non_silent"); break
                            }
                        }
                    }
                    if (AudioVisualization.consumers.get() <= 0 || encoding != C.ENCODING_PCM_16BIT) return
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastSample < 50) return
                    lastSample = now
                    val source = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
                    val count = source.remaining() / 2
                    if (count == 0) return
                    val start = source.position()
                    val result = List(24) { bin ->
                        val first = bin * count / 24
                        val last = ((bin + 1) * count / 24).coerceAtLeast(first + 1).coerceAtMost(count)
                        var sum = 0f
                        val stride = ((last - first) / 8).coerceAtLeast(1)
                        var samples = 0
                        var i = first
                        while (i < last) { sum += kotlin.math.abs(source.getShort(start + i * 2).toInt()) / 32768f; samples++; i += stride }
                        (sum / samples.coerceAtLeast(1) * 3f).coerceIn(0f, 1f)
                    }
                    AudioVisualization.publish(result)
                }
            }))).build()
}
