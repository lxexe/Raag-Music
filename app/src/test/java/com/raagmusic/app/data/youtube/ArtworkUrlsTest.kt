package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class ArtworkUrlsTest {
    @Test fun upgradesKnownMusicCoversWithoutRemovingTheirCropParameters() {
        assertEquals("https://lh3.googleusercontent.com/cover=w1024-h1024-l90-rj",
            ArtworkUrls.highResolution("https://lh3.googleusercontent.com/cover=w60-h60-l90-rj"))
    }
    @Test fun supportsSquareCdnSizes() {
        assertEquals("https://yt3.ggpht.com/art=s1024-rj", ArtworkUrls.highResolution("https://yt3.ggpht.com/art=s120-rj"))
    }
    @Test fun doesNotRewriteOtherProvidersOrLookalikeDomains() {
        val url = "https://googleusercontent.com.example.org/art=w60-h60?signature=secret"
        assertEquals(url, ArtworkUrls.highResolution(url))
    }
    @Test fun fullPlayerRequestsMaxresVideoArtworkWhileRowsRetainTheirOriginal() {
        val url = "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg?sqp=test"
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg?sqp=test", ArtworkUrls.highResolution(url))
        assertEquals(url, ArtworkUrls.highResolution(url, 512))
    }
    @Test fun localArtworkAndMissingUrlsAreSafe() {
        assertEquals("", ArtworkUrls.highResolution(""))
        assertEquals("asset://cover.jpg", ArtworkUrls.highResolution("asset://cover.jpg"))
    }
}
