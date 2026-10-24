package com.raagmusic.app

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.playback.CrossfadeController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Counts delivered PCM and sink/decoder reuse; this does not capture physical speaker output. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class GaplessContinuityTest {
    @Test fun compatibleContinuousPcmReusesAudioTrackWithoutInsertedSilentFrames() = runBlocking { verify(listOf(8_000, 8_000), true) }
    @Test fun mixedSampleRatesFinishWithoutPlayerFailureOrLostPcm() = runBlocking { verify(listOf(8_000, 16_000), false) }

    private suspend fun verify(rates: List<Int>, compatible: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = rates.mapIndexed { i, rate -> tone(File(context.cacheDir, "gapless-$i.wav"), rate) }
        val samples = AtomicLong(); val longestZeros = AtomicInteger(); val decoders = AtomicInteger(); val underruns = AtomicInteger()
        val audioTracks = AtomicInteger()
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                    private var zeros = 0
                    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) { assertEquals(C.ENCODING_PCM_16BIT, encoding) }
                    override fun handleBuffer(buffer: ByteBuffer) {
                        val data = buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
                        while (data.remaining() >= 2) {
                            if (data.short.toInt() == 0) { zeros++; longestZeros.updateAndGet { maxOf(it, zeros) } } else zeros = 0
                            samples.incrementAndGet()
                        }
                    }
                }))).build()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var player: ExoPlayer? = null
        var transitions: CrossfadeController? = null
        try {
            withContext(Dispatchers.Main) {
                player = ExoPlayer.Builder(context, renderers).build().apply {
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, timestamp: Long, duration: Long) { decoders.incrementAndGet() }
                        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) { underruns.incrementAndGet() }
                        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: AudioSink.AudioTrackConfig) { audioTracks.incrementAndGet() }
                    })
                    setMediaItems(files.mapIndexed { i, file -> MediaItem.Builder().setMediaId("part$i").setUri(file.toURI().toString()).build() })
                    prepare(); play()
                }
                transitions = CrossfadeController(player!!, scope, { error("Gapless must not create a second decoder") })
            }
            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { player!!.playerError?.let { throw it }; player!!.playbackState != Player.STATE_ENDED }) delay(20)
            }
            assertEquals(rates.sumOf { it.toLong() * 2 }, samples.get())
            assertTrue("No inserted block of silence between continuous PCM tracks", longestZeros.get() <= 2)
            // Raw PCM bypasses a codec. A single AudioTrack and no underrun prove continuity
            // through the sink without pretending to test compressed encoder-delay trimming.
            if (compatible) { assertEquals(0, decoders.get()); assertEquals(1, audioTracks.get()); assertEquals(0, underruns.get()) }
            android.util.Log.i("RaagGaplessProbe", "RESULT compatible=$compatible samples=${samples.get()} zeroRun=${longestZeros.get()} decoderInitializations=${decoders.get()} audioTracks=${audioTracks.get()} underruns=${underruns.get()}")
        } finally {
            withContext(Dispatchers.Main) { transitions?.release(); player?.release(); scope.cancel() }
            files.forEach(File::delete)
        }
    }
    private fun tone(file: File, rate: Int): File {
        val frames = rate * 2
        val data = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { data.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / rate) * 2_000).toInt().toShort()) }
        return file.apply { writeBytes(data.array()) }
    }
}
