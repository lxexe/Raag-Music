package com.raagmusic.app.data

import com.raagmusic.app.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeSnapshotStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val song = Track("abcdefghijk", "Song", "Singer", "https://example.com/art.jpg", "https://signed.example.com/audio?secret=token", Source.YOUTUBE)
    @Test fun startupSnapshotIsRegionAndMoodScopedAndExpires() {
        var time = 100_000_000L
        val cache = HomeSnapshotStore(File(folder.root, "home.json")) { time }
        cache.write("IN", "For you", listOf(song))
        assertEquals(song.id, cache.read("IN", "For you").single().id)
        assertTrue(cache.read("US", "For you").isEmpty())
        assertTrue(cache.read("IN", "Sleep").isEmpty())
        time += 86_400_001
        assertTrue(cache.read("IN", "For you").isEmpty())
    }
    @Test fun snapshotNeverPersistsPlayableUrlsOrLocalFiles() {
        val file = File(folder.root, "home.json")
        val cache = HomeSnapshotStore(file)
        cache.write("IN", "For you", listOf(song, song.copy(id = "local-song", source = Source.LOCAL, uri = "file:///private/music.mp3")))
        assertFalse(file.readText().contains("signed.example.com"))
        assertFalse(file.readText().contains("private/music"))
        val loaded = cache.read("IN", "For you").single()
        assertEquals(song.id, loaded.uri); assertEquals(Source.YOUTUBE, loaded.source)
    }
    @Test fun damagedSnapshotCannotPreventStartup() {
        val file = File(folder.root, "home.json").apply { writeText("broken json") }
        assertTrue(HomeSnapshotStore(file).read("IN", "For you").isEmpty())
    }
    @Test fun changingListeningTasteCannotRestoreAnUnrelatedSnapshot() {
        val cache = HomeSnapshotStore(File(folder.root, "home.json"))
        cache.write("IN", "For you", listOf(song), taste = "old-artists")
        assertEquals(1, cache.read("IN", "For you", taste = "old-artists").size)
        assertTrue(cache.read("IN", "For you", taste = "new-artists").isEmpty())
    }

}
