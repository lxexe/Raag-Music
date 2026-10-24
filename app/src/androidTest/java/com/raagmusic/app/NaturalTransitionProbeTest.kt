package com.raagmusic.app

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@UnstableApi
@RunWith(AndroidJUnit4::class)
class NaturalTransitionProbeTest {
    @Test fun readyNextSourceSurvivesSeekAndHandsOffAtNaturalEnd() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val previous = app.features.state.first(); val library = app.library.state.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val events = CopyOnWriteArrayList<PlaybackTrace.Event>()
        var connection: PlayerConnection? = null
        suspend fun eventually(check: () -> Boolean) = withTimeout(30_000) { while (!check()) delay(20) }
        try {
            app.features.update { FeatureState(preferences = ListeningPreferences(rememberQueue = false, collectHistory = false)) }
            PlaybackTrace.observer = { events.add(it); Log.i("RaagNaturalProbe", "STAGE id=${it.id} stage=${it.stage} at=${it.atMs} ms=${it.durationMs}") }
            val tracks = listOf("5NV6Rdv1a3I", "dQw4w9WgXcQ").map { Track(it, "Natural transition probe", "Probe", "", it, Source.YOUTUBE) }
            withContext(Dispatchers.Main) { connection = PlayerConnection(app, scope); connection!!.play(tracks.first(), tracks, ordered = true) }
            eventually { connection!!.state.value.playing && events.any { it.id == tracks[1].id && it.stage == "preload_samples_ready" } }
            val before = events.size
            withContext(Dispatchers.Main) { connection!!.seek((connection!!.state.value.duration - 1_500).coerceAtLeast(0)) }
            eventually { connection!!.state.value.trackId == tracks[1].id && connection!!.state.value.playing }
            delay(300)
            Assert.assertTrue("A ready source must survive current-track seek buffering", events.drop(before).any { it.id == tracks[1].id && it.stage == "preloaded_source_handoff" })
            Assert.assertNull(connection!!.state.value.error)
            Log.i("RaagNaturalProbe", "RESULT readySourceReused=true nextDecoderInitializations=${events.drop(before).count { it.id == tracks[1].id && it.stage == "decoder_ready" }} decoderReuse=${events.drop(before).count { it.id == tracks[1].id && it.stage == "decoder_reuse_yes" }} nextSinkInitializations=${events.drop(before).count { it.id == tracks[1].id && it.stage == "sink_initialized" }} underruns=${events.drop(before).count { it.stage == "audio_underrun" }}")
        } finally {
            withContext(Dispatchers.Main) { connection?.pause(); connection?.close() }
            scope.cancel(); PlaybackTrace.observer = null
            app.features.update { previous }; app.library.update { library }
        }
        Unit
    }
}
