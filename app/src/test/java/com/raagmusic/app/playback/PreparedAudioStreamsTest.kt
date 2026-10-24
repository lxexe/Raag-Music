package com.raagmusic.app.playback

import com.raagmusic.app.data.youtube.ResolvedAudioStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PreparedAudioStreamsTest {
    @Test fun currentPlaySeekNeverWaitsForAnUnrelatedWarmup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val streams = PreparedAudioStreams({ id, _ ->
            if (id == "next") { entered.complete(Unit); finish.await() }
            ResolvedAudioStream("url-$id")
        })
        streams.resolve("current-play", "current", 0)
        val warm = async { streams.resolve("next-play", "next", 0, warm = true) }
        try {
            entered.await()
            withTimeout(1_000) { assertEquals("url-current", streams.resolve("current-play", "current", 0).url) }
        } finally { finish.complete(Unit); warm.await() }
    }
    @Test fun warmupCrossfadeAndMainHandoffShareOneExtraction() = runBlocking {
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ -> ResolvedAudioStream("url-${++calls}") })
        val warm = streams.resolve("next-play", "song", 0, warm = true)
        assertEquals(warm, streams.resolve("next-play", "song", 0))
        streams.resolve("outgoing-play", "other", 0)
        assertEquals(warm, streams.resolve("next-play", "song", 0))
        assertEquals(2, calls)
    }

    @Test fun simultaneousWarmupAndPlaybackCoalesce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ -> calls++; entered.complete(Unit); finish.await(); ResolvedAudioStream("url") })
        val warm = async { streams.resolve("play", "song", 0, warm = true) }
        entered.await()
        val playback = async { streams.resolve("play", "song", 0) }
        finish.complete(Unit)
        assertEquals(warm.await(), playback.await())
        assertEquals(1, calls)
    }

    @Test fun clickingTheSameSongDuringWarmupCoalescesAcrossPlayTokens() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ -> calls++; entered.complete(Unit); finish.await(); ResolvedAudioStream("url") })
        val warm = async { streams.resolve("queued-token", "song", 0, warm = true) }
        entered.await()
        val click = async { streams.resolve("new-click-token", "song", 0) }
        yield()
        assertFalse(warm.isCancelled)
        finish.complete(Unit)
        assertEquals(warm.await(), click.await())
        assertEquals(1, calls)
    }

    @Test fun changedQualityReplacesWarmupButKeepsStartedEncodingStable() = runBlocking {
        val limits = mutableListOf<Int>()
        val streams = PreparedAudioStreams({ _, limit -> limits += limit; ResolvedAudioStream("url-$limit") })
        streams.resolve("play", "song", 0, warm = true)
        assertEquals("url-64000", streams.resolve("play", "song", 64_000).url)
        assertEquals("url-64000", streams.resolve("play", "song", 0).url)
        assertEquals(64_000, streams.resolvedLimit("play"))
        assertEquals(listOf(0, 64_000), limits)
    }

    @Test fun expiryAndErrorInvalidationForceFreshExtraction() = runBlocking {
        var now = 0L
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ -> ResolvedAudioStream("url-${++calls}", expiresAtEpochMillis = now + 30_000) }, { now }, { now })
        streams.resolve("play", "song", 0)
        now = 26_000
        assertEquals("url-2", streams.resolve("play", "song", 0).url)
        streams.invalidate()
        assertEquals("url-3", streams.resolve("play", "song", 0).url)
    }

    @Test fun oldWarmupAndLongHorizonAreRefreshed() = runBlocking {
        var now = 0L
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ -> ResolvedAudioStream("url-${++calls}") }, { now })
        streams.resolve("play", "song", 0, warm = true)
        streams.resolve("play", "song", 0, warm = true, requiredValidityMillis = PreparedAudioStreams.WARM_TTL_MS)
        assertEquals(2, calls)
        now = PreparedAudioStreams.WARM_TTL_MS
        assertEquals("url-3", streams.resolve("play", "song", 0).url)
    }

    @Test fun invalidationDuringPreparationDoesNotRetainTheResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ ->
            if (++calls == 1) { entered.complete(Unit); finish.await() }
            ResolvedAudioStream("url-$calls")
        })
        val old = async { streams.resolve("play", "song", 0) }
        entered.await(); streams.invalidate(); finish.complete(Unit); old.await()
        assertEquals("url-2", streams.resolve("play", "song", 0).url)
    }

    @Test fun newPlayTokensReuseFreshEncodingAndInvalidationForcesExtraction() = runBlocking {
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ -> ResolvedAudioStream("url-${++calls}") })
        for (token in listOf("one", "two", "three", "one")) streams.resolve(token, "song", 0)
        assertEquals(1, calls)
        streams.invalidate("one")
        streams.resolve("four", "song", 0)
        assertEquals(2, calls)
    }

    @Test fun coldSelectionCancelsAnUnrelatedSpeculativeExtraction() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val streams = PreparedAudioStreams({ id, _ ->
            if (id == "speculative") { entered.complete(Unit); awaitCancellation() }
            ResolvedAudioStream("url-$id")
        })
        val warm = launch { streams.resolve("warm", "speculative", 0, warm = true) }
        entered.await()
        withTimeout(1_000) { assertEquals("url-selected", streams.resolve("selected", "selected", 0).url) }
        warm.join()
        assertTrue(warm.isCancelled)
    }

    @Test fun recentPlaybackReuseHonorsQualityExpiryAndCapacity() = runBlocking {
        var calls = 0
        var now = 0L
        val streams = PreparedAudioStreams({ id, limit ->
            ResolvedAudioStream("$id-$limit-${++calls}", expiresAtEpochMillis = now + 120_000)
        }, { now }, { now })
        streams.resolve("first", "song", 0)
        streams.resolve("second", "song", 0)
        assertEquals(1, calls)
        streams.resolve("lower-quality", "song", 64_000)
        assertEquals(2, calls)
        now = 90_000
        streams.resolve("near-expiry", "song", 0)
        assertEquals(3, calls)
        repeat(34) { streams.resolve("play-$it", "song-$it", 0) }
        streams.resolve("evicted", "song", 0)
        assertEquals(38, calls)
    }

    @Test fun failureAndCancellationDoNotPoisonPlaybackPreparation() = runBlocking {
        var calls = 0
        val streams = PreparedAudioStreams({ _, _ -> when (++calls) {
            1 -> throw IOException("offline")
            2 -> throw CancellationException("cancelled")
            else -> ResolvedAudioStream("working")
        } })
        try { streams.resolve("play", "song", 0); fail("Expected failure") } catch (_: IOException) { }
        try { streams.resolve("play", "song", 0); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals("working", streams.resolve("play", "song", 0).url)
    }

    @Test fun byteCacheReusesEncodingAcrossSignaturesAndSeparatesOtherFormats() {
        val first = ResolvedAudioStream("https://cdn/audio?sig=secret-one", formatIdentity = "251|original|123|456")
        val rotated = first.copy(url = "https://cdn/audio?sig=secret-two")
        val other = first.copy(formatIdentity = "140|original|321|456")
        assertEquals(first.cacheKey("song", "play-1"), rotated.cacheKey("song", "play-2"))
        assertNotEquals(first.cacheKey("song", "play-1"), other.cacheKey("song", "play-1"))
        assertNotEquals(first.cacheKey("song", "play-1"), first.cacheKey("other-song", "play-1"))
        assertFalse(first.cacheKey("song", "play-1").contains("secret"))
        val unidentified = first.copy(formatIdentity = null)
        assertNotEquals(unidentified.cacheKey("song", "play-1"), unidentified.cacheKey("song", "play-2"))
    }
    @Test fun warmupSurvivesAnOrdinarySongAndErrorKeepsTheNextIntro() = runBlocking {
        var now = 0L
        var calls = 0
        val streams = PreparedAudioStreams({ id, _ -> calls++; ResolvedAudioStream("url-$id-$calls") }, { now })
        streams.resolve("current", "first", 0)
        val intro = streams.resolve("next", "second", 0, warm = true)
        now = 240_000
        streams.invalidate("current")
        assertEquals(intro, streams.resolve("next", "second", 0))
        assertEquals(2, calls)
    }

    @Test fun rejectedEvictedTokenInvalidatesRecentAndOtherActivePlaysOfTheSameSong() = runBlocking {
        var calls = 0
        val streams = PreparedAudioStreams({ id, _ -> ResolvedAudioStream("$id-${++calls}") })
        streams.resolve("youtube://track/first?play=one", "first", 0)
        streams.resolve("youtube://track/second?play=two", "second", 0)
        streams.resolve("youtube://track/third?play=three", "third", 0)
        streams.resolve("youtube://track/first?play=four", "first", 0)
        assertEquals(3, calls)
        streams.invalidate("youtube://track/first?play=one")
        assertEquals("first-4", streams.resolve("youtube://track/first?play=four", "first", 0).url)
        assertEquals(4, calls)
    }

}
