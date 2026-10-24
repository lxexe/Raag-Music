package com.raagmusic.app

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.playback.AudioVisualization
import com.raagmusic.app.playback.VisualizationRenderers
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioVisualizationTest {
    @Test fun decodedAudioProducesLevelsAndStoppingConsumerStopsSampling() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "visualizer-fixture.wav")
        val samples = 8000 * 4
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort((sin(it * 2.0 * Math.PI * 440 / 8000) * 14000).toInt().toShort()) }
        file.writeBytes(bytes.array())
        val player = withContext(Dispatchers.Main) { ExoPlayer.Builder(context, VisualizationRenderers(context)).build() }
        AudioVisualization.consumers.incrementAndGet()
        try {
            withContext(Dispatchers.Main) { player.setMediaItem(MediaItem.fromUri(file.toURI().toString())); player.prepare(); player.play() }
            withTimeout(8000) { while (AudioVisualization.levels.value.none { it > .1f }) delay(50) }
            assertEquals(24, AudioVisualization.levels.value.size)
            assertTrue(AudioVisualization.levels.value.all { it in 0f..1f })
            AudioVisualization.consumers.decrementAndGet()
            delay(120)
            val frozen = AudioVisualization.levels.value
            delay(180)
            assertEquals(frozen, AudioVisualization.levels.value)
        } finally {
            withContext(Dispatchers.Main) { player.release() }
            file.delete()
            AudioVisualization.consumers.set(0)
        }
    }
}
