package com.raagmusic.app

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.Track
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(AndroidJUnit4::class)
class RecommendationQueueTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var audio: File
    private lateinit var player: ExoPlayer
    private lateinit var scope: CoroutineScope
    private lateinit var chain: RecommendationQueue
    private val requests = mutableListOf<String>()
    private var error: String? = null
    private var loading = false
    private fun song(id: String) = Track(id, id, "Artist", "", audio.toURI().toString())
    private fun ids() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    @Before fun setup() = runBlocking {
        audio = File(context.cacheDir, "recommendation-chain.wav")
        val size = 8000 * 2 * 3
        val buffer = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        audio.writeBytes(buffer.array())
        withContext(Dispatchers.Main) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    if (::chain.isInitialized && reason == Player.DISCONTINUITY_REASON_SEEK) chain.cancelPendingAdvance()
                }
                override fun onEvents(p: Player, events: Player.Events) {
                    if (::chain.isInitialized) {
                        if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !p.playWhenReady) chain.cancelPendingAdvance()
                        chain.sync()
                    }
                }
            })
        }
    }
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) {
            if (::chain.isInitialized) chain.close()
            scope.cancel(); player.release()
        }
        audio.delete()
        Unit
    }
    private fun start(targetAhead: Int = 1, lowWater: Int = 0, loader: suspend (MediaItem) -> List<Track>) {
        chain = RecommendationQueue(player, scope, related = { requests += it.mediaId; loader(it) },
            remember = {}, targetAhead = targetAhead, lowWater = lowWater, status = { _, busy, failure -> loading = busy; error = failure })
        player.setMediaItem(song("A").asMediaItem(recommendationChain = true))
        player.prepare() // paused unless explicitly started
        chain.sync()
    }
    private suspend fun eventually(predicate: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(12_000) { while (!predicate()) delay(30) }
    }

    @Test fun eachSuccessorUsesCurrentSongAndPreviousKeepsHistory() = runBlocking {
        withContext(Dispatchers.Main) { start { item ->
            listOf(song("A"), song(item.mediaId), song((item.mediaId.single() + 1).toString()))
        } }
        eventually { ids() == listOf("A", "B") }
        withContext(Dispatchers.Main) { chain.next() }
        eventually { player.currentMediaItem?.mediaId == "B" && ids() == listOf("A", "B", "C") }
        withContext(Dispatchers.Main) { chain.next() }
        eventually { player.currentMediaItem?.mediaId == "C" && ids() == listOf("A", "B", "C", "D") }
        withContext(Dispatchers.Main) {
            player.pause(); player.seekToPreviousMediaItem()
            assertEquals("B", player.currentMediaItem?.mediaId)
            player.seekToPreviousMediaItem()
            assertEquals("A", player.currentMediaItem?.mediaId)
            chain.next()
            assertEquals("B", player.currentMediaItem?.mediaId)
            assertEquals(listOf("A", "B", "C"), requests)
            assertEquals(listOf("A", "B", "C", "D"), ids())
            player.shuffleModeEnabled = true
            assertTrue(player.shuffleModeEnabled)
            chain.next()
            assertTrue(player.currentMediaItem?.mediaId in ids())
            player.repeatMode = Player.REPEAT_MODE_ONE
            assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
            player.repeatMode = Player.REPEAT_MODE_ALL
            assertEquals(Player.REPEAT_MODE_ALL, player.repeatMode)
        }
    }

    @Test fun nextWaitsForRelatedLookupAndRepeatedTapsAdvanceOnce() = runBlocking {
        val gate = CompletableDeferred<List<Track>>()
        withContext(Dispatchers.Main) { start { gate.await() }; chain.next(); chain.next() }
        eventually { requests == listOf("A") && loading }
        gate.complete(listOf(song("B")))
        eventually { player.currentMediaItem?.mediaId == "B" }
        withContext(Dispatchers.Main) { assertEquals(listOf("A", "B"), ids()); assertFalse(player.playWhenReady) }
    }

    @Test fun replacingQueueCancelsStaleRecommendationsAndExplicitQueuesStayExplicit() = runBlocking {
        val gate = CompletableDeferred<List<Track>>()
        withContext(Dispatchers.Main) { start { withContext(NonCancellable) { gate.await() } } }
        eventually { requests == listOf("A") }
        withContext(Dispatchers.Main) { player.setMediaItems(listOf(song("X").asMediaItem(), song("Y").asMediaItem())); chain.sync() }
        gate.complete(listOf(song("B")))
        delay(200)
        withContext(Dispatchers.Main) {
            assertEquals(listOf("X", "Y"), ids()); assertFalse(chain.enabled)
            chain.next(); assertEquals("Y", player.currentMediaItem?.mediaId)
        }
        delay(900)
        withContext(Dispatchers.Main) { assertEquals(listOf("A"), requests); assertEquals(listOf("X", "Y"), ids()) }
    }

    @Test fun failureCanRetryWithoutFallingBackToSearchResults() = runBlocking {
        var fail = true
        withContext(Dispatchers.Main) { start { if (fail) error("Provider unavailable") else listOf(song("B")) } }
        eventually { error != null && !loading }
        withContext(Dispatchers.Main) { assertEquals(listOf("A"), ids()); fail = false; chain.next() }
        eventually { player.currentMediaItem?.mediaId == "B" }
    }

    @Test fun lateRecommendationContinuesAfterNaturalEnd() = runBlocking {
        val gate = CompletableDeferred<List<Track>>()
        withContext(Dispatchers.Main) { start { gate.await() }; player.play() }
        eventually { player.playbackState == Player.STATE_ENDED }
        gate.complete(listOf(song("B")))
        eventually { player.currentMediaItem?.mediaId == "B" && player.isPlaying }
        withContext(Dispatchers.Main) { player.pause() }
    }

    @Test fun repeatOneLoopsCurrentAndRepeatAllLoopsExistingQueue() = runBlocking {
        var repeats = 0
        withContext(Dispatchers.Main) {
            start { item -> listOf(song((item.mediaId.single() + 1).toString())) }
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeats++
                }
            })
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.play()
        }
        eventually { ids() == listOf("A", "B") && player.isPlaying }
        withContext(Dispatchers.Main) { player.seekTo(2_800) }
        eventually { repeats > 0 }
        withContext(Dispatchers.Main) {
            assertEquals("A", player.currentMediaItem?.mediaId)
            player.repeatMode = Player.REPEAT_MODE_ALL
            chain.next()
        }
        eventually { player.currentMediaItem?.mediaId == "B" }
        withContext(Dispatchers.Main) {
            assertEquals(0, player.upcomingQueueIndices().first())
            player.seekTo(2_800)
        }
        eventually { player.currentMediaItem?.mediaId == "A" }
        withContext(Dispatchers.Main) {
            player.pause()
            assertEquals(listOf("A", "B"), ids())
            assertEquals(listOf("A"), requests)
        }
    }

    @Test fun shuffleTailGetsItsOwnRelatedSuccessorWithoutDisablingShuffle() = runBlocking {
        withContext(Dispatchers.Main) { start { item -> listOf(song("${item.mediaId}-related")) } }
        eventually { ids().size == 2 }
        withContext(Dispatchers.Main) {
            player.shuffleModeEnabled = true
            val tail = player.currentTimeline.getLastWindowIndex(true)
            player.seekTo(tail, 0)
            chain.sync()
        }
        eventually { ids().size == 3 }
        withContext(Dispatchers.Main) {
            val current = player.currentMediaItem!!.mediaId
            val next = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
            assertEquals("$current-related", next)
            assertTrue(player.shuffleModeEnabled)
            chain.next()
            assertEquals(next, player.currentMediaItem?.mediaId)
            assertTrue(player.shuffleModeEnabled)
        }
    }

    @Test fun pauseCancelsPendingAdvanceWhileKeepingTheRecommendation() = runBlocking {
        val gate = CompletableDeferred<List<Track>>()
        withContext(Dispatchers.Main) { start { gate.await() }; player.play(); chain.next() }
        eventually { loading && requests == listOf("A") }
        withContext(Dispatchers.Main) { player.pause() }
        eventually { !player.playWhenReady }
        gate.complete(listOf(song("B")))
        eventually { ids() == listOf("A", "B") }
        withContext(Dispatchers.Main) {
            assertEquals("A", player.currentMediaItem?.mediaId)
            assertFalse(player.playWhenReady)
        }
    }
    @Test fun runwayRefillsBeforeEndAndRapidNextUsesReadyItems() = runBlocking {
        withContext(Dispatchers.Main) { start(targetAhead = 8, lowWater = 3) { item ->
            (1..10).map { song("${item.mediaId}-$it") }
        } }
        eventually { ids().size == 9 }
        withContext(Dispatchers.Main) { repeat(5) { chain.next() } }
        eventually { ids().size == 14 }
        withContext(Dispatchers.Main) {
            assertEquals("A-5", player.currentMediaItem?.mediaId)
            assertEquals("A-6", player.getMediaItemAt(player.upcomingQueueIndices().first()).mediaId)
            assertEquals(8, player.upcomingQueueIndices().size)
            assertEquals(listOf("A", "A-8"), requests)
        }
    }

    @Test fun shuffleProjectionMatchesNativeNextAndSurvivesRefill() = runBlocking {
        withContext(Dispatchers.Main) { start(targetAhead = 8, lowWater = 3) { item ->
            (1..10).map { song("${item.mediaId}-$it") }
        } }
        eventually { ids().size == 9 }
        withContext(Dispatchers.Main) {
            player.shuffleModeEnabled = true
            assertEquals(player.nextMediaItemIndex, player.upcomingQueueIndices().first())
            val tail = player.currentTimeline.getLastWindowIndex(true)
            player.seekTo(tail, 0)
            chain.sync()
        }
        eventually { player.upcomingQueueIndices().size == 8 }
        withContext(Dispatchers.Main) {
            val next = player.getMediaItemAt(player.upcomingQueueIndices().first()).mediaId
            chain.next()
            assertEquals(next, player.currentMediaItem?.mediaId)
            assertTrue(player.shuffleModeEnabled)
        }
    }

    @Test fun longRadioSessionsRetainBoundedHistoryAndCurrentPosition() = runBlocking {
        withContext(Dispatchers.Main) { start(targetAhead = 8, lowWater = 3) { item ->
            (1..10).map { song("${item.mediaId}-$it") }
        } }
        eventually { ids().size == 9 }
        repeat(70) {
            eventually { player.hasNextMediaItem() }
            withContext(Dispatchers.Main) { chain.next() }
        }
        eventually { !loading }
        withContext(Dispatchers.Main) {
            assertTrue(player.currentMediaItemIndex <= 54)
            assertTrue(player.mediaItemCount <= 63)
            assertTrue(player.hasNextMediaItem())
            assertNull(error)
        }
    }

    @Test fun shuffledRadioAlsoBoundsPlayedHistory() = runBlocking {
        withContext(Dispatchers.Main) { start(targetAhead = 8, lowWater = 3) { item ->
            (1..10).map { song("${item.mediaId}-$it") }
        } }
        eventually { ids().size == 9 }
        withContext(Dispatchers.Main) { player.shuffleModeEnabled = true }
        repeat(70) {
            eventually { player.hasNextMediaItem() }
            withContext(Dispatchers.Main) { chain.next() }
        }
        eventually { !loading }
        withContext(Dispatchers.Main) {
            assertTrue(player.mediaItemCount <= 63)
            assertTrue(player.hasNextMediaItem()); assertTrue(player.shuffleModeEnabled)
            assertNull(error)
        }
    }

}
