package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class AudioSessionStoreTest {
    @Test fun restartReusesOnlyYoungMatchingRegionContextAndResetRemovesIt() {
        val directory = Files.createTempDirectory("audio-session-test").toFile()
        var now = 1_000_000L
        try {
            AudioSessionStore(directory) { now }.write("IN", "fixture-visitor")
            assertEquals("fixture-visitor", AudioSessionStore(directory) { now }.read("IN"))
            assertNull(AudioSessionStore(directory) { now }.read("US"))
            now += AudioSessionStore.LIFETIME_MS
            assertNull(AudioSessionStore(directory) { now }.read("IN"))
            now = 999_999L
            assertNull(AudioSessionStore(directory) { now }.read("IN"))
            AudioSessionStore(directory).clear()
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptOrOversizedContextNeverBlocksPlayback() {
        val directory = Files.createTempDirectory("audio-session-test").toFile()
        try {
            val store = AudioSessionStore(directory)
            store.write("IN", "https://not-a-context")
            assertNull(store.read("IN"))
            java.io.File(directory, "native-session.json").writeText("x".repeat(9_000))
            assertNull(store.read("IN"))
        } finally { directory.deleteRecursively() }
    }
}
