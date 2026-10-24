package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class SharedMusicLinkTest {
    @Test fun parsesSharedTextAndCanonicalizesSongTimestamp() {
        assertEquals(SharedMusicLink.Song("5NV6Rdv1a3I", 83000), SharedMusicLink.parse("Listen! https://youtu.be/5NV6Rdv1a3I?t=1m23s&si=abc"))
        assertEquals(SharedMusicLink.Song("5NV6Rdv1a3I", 12000), SharedMusicLink.parse("https://music.youtube.com/watch?v=5NV6Rdv1a3I&start=12"))
        assertEquals(SharedMusicLink.Song("5NV6Rdv1a3I"), SharedMusicLink.parse("https://www.youtube.com/shorts/5NV6Rdv1a3I"))
    }
    @Test fun parsesAlbumAndPlaylistReferencesWithoutAcceptingArbitraryUrls() {
        assertEquals(SharedMusicLink.Collection("PLRBp0Fe2GpgnIh0AiYKh7o7HnYAej-5ph"), SharedMusicLink.parse("https://youtube.com/playlist?list=PLRBp0Fe2GpgnIh0AiYKh7o7HnYAej-5ph"))
        assertEquals(SharedMusicLink.Collection("OLAK5uy_abcdefghijk", true), SharedMusicLink.parse("https://music.youtube.com/playlist?list=OLAK5uy_abcdefghijk"))
        assertEquals(SharedMusicLink.Collection("MPREb_abcdefghijk", true), SharedMusicLink.parse("https://music.youtube.com/browse/MPREb_abcdefghijk"))
    }
    @Test fun rejectsSpoofedHostsCredentialsPortsAndInvalidIds() {
        listOf("https://youtube.com.evil.org/watch?v=5NV6Rdv1a3I", "https://youtube.com@evil.org/watch?v=5NV6Rdv1a3I", "https://evil.org@youtube.com/watch?v=5NV6Rdv1a3I", "https://youtube.com:8443/watch?v=5NV6Rdv1a3I", "https://youtube.com/watch?v=bad", "https://youtube.com/channel/abcdefghijk", "file:///watch?v=5NV6Rdv1a3I").forEach { assertNull(it, SharedMusicLink.parse(it)) }
    }
    @Test fun malformedEncodedTextAndUnboundedTimestampsAreSafe() {
        assertNull(SharedMusicLink.parse("https://youtube.com/watch?v=%XX"))
        assertEquals(SharedMusicLink.Song("5NV6Rdv1a3I", 86400000), SharedMusicLink.parse("https://youtu.be/5NV6Rdv1a3I?t=999999999"))
    }
    @Test fun watchWithPlaylistStillOpensSelectedSong() {
        assertEquals(SharedMusicLink.Song("5NV6Rdv1a3I"), SharedMusicLink.parse("https://youtube.com/watch?v=5NV6Rdv1a3I&list=PLabcdefghijk"))
    }
}
