package com.raagmusic.app

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.Source
import com.raagmusic.app.domain.Track
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Uses the real UI connection, session, resolver, cache, decoder and AudioTrack. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackPipelineTest {
    @Test fun tapToAudioOutputAndNext() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("liveProviders") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val previous = app.features.state.first()
        val previousLibrary = app.library.state.first()
        val events = CopyOnWriteArrayList<PlaybackTrace.Event>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var connection: PlayerConnection? = null
        var viewModel: com.raagmusic.app.ui.MusicViewModel? = null
        val viewModels = androidx.lifecycle.ViewModelStore()
        val seed = args.getString("videoId") ?: "5NV6Rdv1a3I"
        val songs = (listOf(seed) + listOf("5NV6Rdv1a3I", "dQw4w9WgXcQ", "kJQP7kiw5Fk").filter { it != seed }).take(3).map { id ->
            Track(id, "Playback probe", "Probe", "", id, source = Source.YOUTUBE, durationSeconds = 240)
        }
        suspend fun output(id: String, after: Long) = withTimeout(45_000) {
            while (events.none { it.id == id && it.stage == "audio_output" && it.atMs >= after }) {
                connection?.state?.value?.error?.let { error(it) }
                delay(20)
            }
        }
        suspend fun play(label: String, queue: List<Track>) {
            val at = System.nanoTime() / 1_000_000
            withContext(Dispatchers.Main) {
                if (viewModel != null) viewModel!!.play(queue.first(), queue, ordered = true, recommendationChain = false)
                else connection!!.play(queue.first(), queue, ordered = true)
            }
            output(queue.first().id, at)
            val end = events.first { it.id == queue.first().id && it.stage == "audio_output" && it.atMs >= at }
            val tap = events.first { it.id == queue.first().id && it.stage == (if (viewModel != null) "ui_selection" else "tap") && it.atMs >= at }
            Log.i("RaagPipeline", "RESULT $label tapToOutputMs=${end.atMs - tap.atMs}")
        }
        try {
            app.features.update { it.copy(preferences = it.preferences.copy(rememberQueue = false, collectHistory = false)) }
            if (args.getString("keepAudioCache") != "true") withContext(Dispatchers.IO) {
                app.media.streamingCache.keys.toList().forEach(app.media.streamingCache::removeResource)
            }
            if (args.getString("clearNativeSession") == "true") withContext(Dispatchers.IO) {
                java.io.File(app.cacheDir, "playback-session").deleteRecursively()
            }
            PlaybackTrace.observer = { event -> events.add(event); Log.i("RaagPipeline", "STAGE id=${event.id} stage=${event.stage} at=${event.atMs} ms=${event.durationMs}") }
            withContext(Dispatchers.Main) {
                if (args.getString("viaViewModel") == "true") {
                    viewModel = com.raagmusic.app.ui.MusicViewModel(app)
                    viewModels.put("playback-probe", viewModel!!)
                    connection = viewModel!!.player
                } else connection = PlayerConnection(app, scope)
            }
            play(if (args.getString("keepAudioCache") == "true") "restart_cached" else "cold", songs)
            delay(args.getString("nextDelayMs")?.toLongOrNull() ?: 7_000)
            val nextAt = System.nanoTime() / 1_000_000
            withContext(Dispatchers.Main) { connection!!.next() }
            output(songs[1].id, nextAt)
            val nextTap = events.first { it.id == songs[1].id && it.stage == "next_tap" && it.atMs >= nextAt }
            val nextLabel = if ((args.getString("nextDelayMs")?.toLongOrNull() ?: 7_000) < 2_000) "early_next" else "preloaded_next"
            Log.i("RaagPipeline", "RESULT $nextLabel tapToOutputMs=${events.first { it.id == songs[1].id && it.stage == "audio_output" && it.atMs >= nextAt }.atMs - nextTap.atMs}")
            if (args.getString("measurePrevious") == "true") {
                val previousAt = System.nanoTime() / 1_000_000
                withContext(Dispatchers.Main) { connection!!.previous() }
                output(songs.first().id, previousAt)
                val previousTap = events.first { it.id == songs.first().id && it.stage == "previous_tap" && it.atMs >= previousAt }
                Log.i("RaagPipeline", "RESULT previous tapToOutputMs=" +
                    (events.first { it.id == songs.first().id && it.stage == "audio_output" && it.atMs >= previousAt }.atMs - previousTap.atMs))
            }
            if (args.getString("rapidNext") == "true") {
                val rapidAt = System.nanoTime() / 1_000_000
                withContext(Dispatchers.Main) { connection!!.play(songs.first(), songs, ordered = true) }
                delay(100)
                withContext(Dispatchers.Main) { connection!!.next(); connection!!.next() }
                output(songs.last().id, rapidAt)
                Assert.assertEquals(songs.last().id, connection!!.state.value.trackId)
                Log.i("RaagPipeline", "RESULT rapid_next finalSelectionCorrect=true tapToOutputMs=" +
                    (events.first { it.id == songs.last().id && it.stage == "audio_output" && it.atMs >= rapidAt }.atMs - rapidAt))
            }
            play("warm_replay", listOf(songs.first()))
        } finally {
            withContext(Dispatchers.Main) { connection?.pause(); if (viewModel != null) viewModels.clear() else connection?.close() }
            scope.cancel(); PlaybackTrace.observer = null
            app.features.update { previous }
            app.library.update { previousLibrary }
        }
        Unit
    }
}
