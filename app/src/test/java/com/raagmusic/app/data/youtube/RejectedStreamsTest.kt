package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class RejectedStreamsTest {
    private fun url(client: String = "VISIONOS", itag: Int = 251, signature: String = "first") =
        "https://r1.googlevideo.com/videoplayback?c=$client&itag=$itag&sig=$signature"
    @Test fun signingRotationCannotReselectTheRejectedClientAndFormat() {
        val streams = RejectedStreams()
        streams.reject("song", url())
        assertFalse(streams.accepts("song", url(signature = "fresh")))
        assertTrue(streams.accepts("song", url(itag = 140)))
        assertTrue(streams.accepts("song", url(client = "ANDROID")))
        assertTrue(streams.accepts("another-song", url()))
        streams.reset("song")
        assertTrue(streams.accepts("song", url()))
    }
    @Test fun temporaryRejectionsExpireWithoutKeepingAnUnboundedHistory() {
        var now = 0L
        val streams = RejectedStreams { now }
        streams.reject("song", url())
        now = 300_000
        assertTrue(streams.accepts("song", url()))
        repeat(65) { streams.reject("song-$it", url()) }
        assertTrue(streams.accepts("song-0", url()))
        assertFalse(streams.accepts("song-64", url()))
    }
}
