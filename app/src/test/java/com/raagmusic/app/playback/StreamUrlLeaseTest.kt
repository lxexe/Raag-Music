package com.raagmusic.app.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StreamUrlLeaseTest {
    @Test fun seekingWithinTheSamePlayAvoidsRepeatedExtraction() {
        var calls = 0
        val lease = StreamUrlLease()
        repeat(5) { assertEquals("url-1", lease.resolve("song:play-1") { "url-${++calls}" }) }
        assertEquals(1, calls)
    }
    @Test fun selectingTheSameSongAgainUsesAFreshPlayToken() {
        var calls = 0
        val lease = StreamUrlLease()
        assertNotEquals(lease.resolve("song:play-1") { "url-${++calls}" }, lease.resolve("song:play-2") { "url-${++calls}" })
        assertEquals(2, calls)
    }
    @Test fun expiredLeasesAreRefreshedBeforeOpeningAudio() {
        var now = 0L
        var calls = 0
        val lease = StreamUrlLease({ now }, 100)
        lease.resolve("song") { "url-${++calls}" }
        now = 100
        assertEquals("url-2", lease.resolve("song") { "url-${++calls}" })
    }
    @Test fun errorInvalidationForcesFreshExtraction() {
        var calls = 0
        val lease = StreamUrlLease()
        lease.resolve("song") { "url-${++calls}" }
        lease.invalidate()
        assertEquals("url-2", lease.resolve("song") { "url-${++calls}" })
    }
    @Test fun invalidationDuringAnExtractionDoesNotRetainItsStaleResult() {
        val lease = StreamUrlLease()
        lease.resolve("song") { lease.invalidate(); "stale-url" }
        assertEquals("fresh-url", lease.resolve("song") { "fresh-url" })
    }
    @Test fun failedExtractionsAreNeverRetained() {
        val lease = StreamUrlLease()
        try { lease.resolve("song") { throw IOException("offline") }; fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals("working-url", lease.resolve("song") { "working-url" })
    }
}
