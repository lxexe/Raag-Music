package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class PlaylistTest {
    @Test fun addingAnExistingSongPreservesItsPosition() {
        val playlist = Playlist("p", "Night drive", listOf("a", "b"))
        assertEquals(listOf("a", "b"), playlist.add("a").trackIds)
    }
    @Test fun editsDoNotMutateTheOriginalLibrarySnapshot() {
        val original = Playlist("p", "Night drive", listOf("a"))
        assertEquals(listOf("a", "b"), original.add("b").trackIds)
        assertEquals(listOf("a"), original.trackIds)
    }
    @Test fun removingASongPreservesTheRestOfTheQueue() {
        assertEquals(listOf("a", "c"), Playlist("p", "Mix", listOf("a", "b", "c")).remove("b").trackIds)
    }
    @Test fun onlineSongsCanDownloadButLocalFilesDoNotNeedDownloading() {
        val video = Track("yt-id", "Video", "Artist", "", "video-id", Source.YOUTUBE)
        assertTrue(video.canDownload)
        assertFalse(video.copy(source = Source.LOCAL).canDownload)
        assertTrue(video.copy(source = Source.DIRECT).canDownload)
    }
}
