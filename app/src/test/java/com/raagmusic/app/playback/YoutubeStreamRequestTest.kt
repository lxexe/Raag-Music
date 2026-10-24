package com.raagmusic.app.playback

import org.junit.Assert.*
import org.junit.Test

class YoutubeStreamRequestTest {
    @Test fun nativeVisionStreamsUseTheirOwnClientIdentity() {
        val headers = youtubeStreamHeaders("https://r1.googlevideo.com/videoplayback?c=VISIONOS")
        assertTrue(headers.getValue("User-Agent").contains("com.google.visionos.youtube"))
        assertFalse(headers.containsKey("Origin"))
        assertEquals("identity", headers["Accept-Encoding"])
    }
    @Test fun webStreamsCarryOriginAndFetchMetadata() {
        val headers = youtubeStreamHeaders("https://r1.googlevideo.com/videoplayback?c=WEB")
        assertEquals("https://www.youtube.com", headers["Origin"])
        assertEquals("cors", headers["Sec-Fetch-Mode"])
        assertFalse(headers.getValue("User-Agent").contains("visionOS"))
    }
    @Test fun requestBodyIsFreshForEveryCall() {
        val first = youtubeStreamBody
        first[0] = 0
        assertArrayEquals(byteArrayOf(0x78, 0), youtubeStreamBody)
    }
}
