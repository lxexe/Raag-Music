package com.raagmusic.app

import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in repeatable CPU/PSS/wakeup proxies, not a physical battery benchmark. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackResourceProbeTest {
    @Test fun compareIdleAndPlaybackResourceUse() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("profilePlayback") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val count = 8_000 * 2 * 60
        val buffer = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count)
        repeat(count / 2) { buffer.putShort((kotlin.math.sin(it * 2 * Math.PI * 440 / 8000) * 2_000).toInt().toShort()) }
        val file = File(context.cacheDir, "resource-probe.wav").apply { writeBytes(buffer.array()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ticks = AtomicInteger()
        var player: ExoPlayer? = null
        var transitions: CrossfadeController? = null
        try {
            PlaybackTrace.observer = { if (it.stage == "transition_tick") ticks.incrementAndGet() }
            withContext(Dispatchers.Main) {
                player = ExoPlayer.Builder(context).build().apply {
                    setMediaItems(listOf("one", "two").map { MediaItem.Builder().setMediaId(it).setUri(file.toURI().toString()).build() })
                }
                transitions = CrossfadeController(player!!, scope, { error("Far from a transition: no second decoder expected") })
            }
            for (phase in listOf("paused", "gapless_playing", "crossfade_far_from_end")) {
                withContext(Dispatchers.Main) {
                    if (phase != "paused") { player!!.prepare(); player!!.play() }
                    transitions!!.configure(ListeningPreferences(transitionMode = if (phase.startsWith("crossfade")) TransitionMode.CROSSFADE else TransitionMode.GAPLESS))
                }
                delay(1_000)
                ticks.set(0)
                val cpu = Process.getElapsedCpuTime()
                val gc = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0
                val elapsed = android.os.SystemClock.elapsedRealtime()
                delay(10_000)
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                Log.i("RaagResourceProbe", "RESULT phase=$phase elapsedMs=${android.os.SystemClock.elapsedRealtime()-elapsed} cpuMs=${Process.getElapsedCpuTime()-cpu} pssKb=${memory.totalPss} nativeHeapKb=${Debug.getNativeHeapAllocatedSize()/1024} gcCount=${(Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0)-gc} transitionTicks=${ticks.get()}")
                if (args.getString("assertNoIdlePolling") == "true") Assert.assertEquals("No periodic transition work in $phase", 0, ticks.get())
            }
        } finally {
            withContext(Dispatchers.Main) { transitions?.release(); player?.release(); scope.cancel() }
            PlaybackTrace.observer = null; file.delete()
        }
    }
}
