package com.raagmusic.app

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerRefinementsTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
    private lateinit var originalLibrary: LibraryState
    private lateinit var originalFeatures: FeatureState
    private lateinit var scope: CoroutineScope
    private lateinit var connection: PlayerConnection
    private lateinit var audio: File
    private lateinit var a: Track
    private lateinit var b: Track
    private lateinit var c: Track

    @Before fun setup() = runBlocking {
        originalLibrary = app.library.state.first()
        originalFeatures = app.features.state.first()
        // Isolate the missing-recommendation fixture from songs saved by other/live runs.
        app.library.update { LibraryState() }
        app.features.update { it.copy(queue = QueueSnapshot(), sleepTimer = SleepTimerSpec(),
            preferences = it.preferences.copy(rememberQueue = false, collectHistory = false, transitionMode = TransitionMode.GAPLESS)) }
        audio = File(app.cacheDir, "player-refinements.wav")
        val count = 8000 * 2 * 8
        val bytes = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count)
        audio.writeBytes(bytes.array())
        a = Track("refinement-a", "A", "Artist", "", audio.toURI().toString())
        b = a.copy(id = "refinement-b", title = "B")
        c = a.copy(id = "refinement-c", title = "C")
        withContext(Dispatchers.Main) { scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    }
    @After fun cleanup() = runBlocking {
        app.features.update { it.copy(sleepTimer = SleepTimerSpec()) }
        withContext(Dispatchers.Main) {
            if (::connection.isInitialized) {
                connection.pause()
                connection.state.value.queueIds.indices.reversed().forEach(connection::removeQueue)
            }
        }
        delay(250)
        withContext(Dispatchers.Main) { if (::connection.isInitialized) connection.close(); scope.cancel() }
        app.stopService(Intent(app, PlaybackService::class.java))
        delay(250)
        app.library.update { originalLibrary }
        app.features.update { originalFeatures }
        audio.delete()
        Unit
    }
    private suspend fun eventually(block: suspend () -> Boolean) = withTimeout(15_000) { while (!block()) delay(40) }
    private suspend fun play() {
        withContext(Dispatchers.Main) { connection = PlayerConnection(app, scope); connection.play(a, listOf(a,b), ordered = true) }
        eventually { connection.state.value.playing && connection.state.value.queueIds == listOf(a.id,b.id) }
    }
    @Test fun recommendationSessionKeepsPauseAndControllerNextCanRetry() = runBlocking {
        suspend fun checkStage(label: String, predicate: () -> Boolean) {
            try { eventually { predicate() } }
            catch (e: TimeoutCancellationException) { throw AssertionError("$label: ${connection.state.value}; saved=${app.features.state.first().queue}", e) }
        }
        withContext(Dispatchers.Main) { connection = PlayerConnection(app, scope); connection.play(a, listOf(a), recommendationChain = true) }
        checkStage("seed") { connection.state.value.recommendationChain && connection.state.value.queueIds == listOf(a.id) }
        withContext(Dispatchers.Main) { connection.pause() }
        checkStage("pause") { !connection.state.value.playWhenReady }
        checkStage("initial lookup") { connection.state.value.recommendationError != null }
        assertFalse(connection.state.value.playWhenReady)
        // Make a local successor available, then verify Next retries without resuming paused audio.
        app.library.update { it.copy(savedTracks = it.savedTracks + b) }
        withContext(Dispatchers.Main) { connection.next() }
        // The service may refill its runway before the next controller poll.
        // Verify the recovered successor and order without racing later recommendations.
        checkStage("retry recovered") { connection.state.value.trackId == b.id && connection.state.value.queueIds.take(2) == listOf(a.id, b.id) }
        assertFalse(connection.state.value.playWhenReady)
        assertFalse(connection.state.value.playing)
    }

    @Test fun queueRequestsBeforeConnectionAreRetainedAndNextMatchesPlayback() = runBlocking {
        withContext(Dispatchers.Main) {
            connection = PlayerConnection(app, scope)
            connection.play(a, listOf(a), ordered = true)
            connection.enqueue(b)
            connection.playNext(c)
        }
        eventually { connection.state.value.queueIds == listOf(a.id,c.id,b.id) }
        assertEquals(1, connection.state.value.nextIndex)
        withContext(Dispatchers.Main) { connection.next() }
        eventually { connection.state.value.trackId == c.id }
        assertEquals(2, connection.state.value.nextIndex)
    }
    @Test fun timerCountsDownAndPausesWithoutAViewModel() = runBlocking {
        play()
        app.features.update { it.copy(sleepTimer = SleepTimerSpec(System.currentTimeMillis() + 3_000)) }
        eventually { connection.state.value.sleepRemainingMs in 1..3_000 }
        val first = connection.state.value.sleepRemainingMs
        eventually { connection.state.value.sleepRemainingMs in 1 until first }
        eventually { !connection.state.value.playWhenReady && !app.features.state.first().sleepTimer.active }
        assertEquals(0L, connection.state.value.sleepRemainingMs)
    }
    @Test fun endOfSongTimerPausesBeforeNextSongAndCancelKeepsPlayback() = runBlocking {
        play()
        app.features.update { it.copy(sleepTimer = SleepTimerSpec(finishTrackId = a.id)) }
        eventually { connection.state.value.sleepEndOfTrack }
        app.features.update { it.copy(sleepTimer = SleepTimerSpec()) }
        eventually { !connection.state.value.sleepEndOfTrack }
        assertTrue(connection.state.value.playWhenReady)
        app.features.update { it.copy(sleepTimer = SleepTimerSpec(finishTrackId = a.id)) }
        eventually { connection.state.value.sleepEndOfTrack }
        withContext(Dispatchers.Main) { connection.seek(7_200) }
        eventually { !connection.state.value.playWhenReady && !app.features.state.first().sleepTimer.active }
        assertTrue(connection.state.value.position < 600 || connection.state.value.trackId == a.id)
    }
    @Test fun immediatePauseBeforeConnectionIsRetained() = runBlocking {
        withContext(Dispatchers.Main) {
            connection = PlayerConnection(app, scope)
            connection.play(a, listOf(a), ordered = true)
            connection.pause()
            assertFalse(connection.state.value.playWhenReady)
            connection.seek(2_000)
        }
        eventually { connection.state.value.trackId == a.id && connection.state.value.duration > 0 && !connection.state.value.buffering }
        delay(300)
        assertFalse(connection.state.value.playWhenReady)
        assertFalse(connection.state.value.playing)
        assertTrue(connection.state.value.position in 1_950..2_050)
    }

}
