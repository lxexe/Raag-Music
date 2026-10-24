package com.raagmusic.app.data

import com.raagmusic.app.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LyricsRepositoryTest {
    private val track = Track("fixture", "Song", "Singer", "", "fixture", durationSeconds = 180)
    @Test fun cachedLyricsDoNotWaitBehindAnotherSongsStalledProvider() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repository = LyricsRepository(listOf(LyricsProvider { song ->
            if (song.id == "slow") { entered.complete(Unit); release.await() }
            Lyrics(plain = "Verse")
        }))
        repository.get(track)
        val slow = async { repository.get(track.copy(id = "slow", title = "Slow song")) }
        try {
            entered.await()
            assertEquals("Verse", withTimeout(200) { repository.get(track) }.plain)
        } finally { release.complete(Unit); slow.await() }
    }
    @Test fun restartUsesSuccessfulLyricsWithoutContactingAProvider() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("lyrics-test").toFile()
        try {
            LyricsRepository(listOf(LyricsProvider { Lyrics(lines = listOf(LyricLine(1_000, "Verse"))) }), directory).get(track)
            val restarted = LyricsRepository(listOf(LyricsProvider { error("Cached lyrics must work offline") }), directory)
            assertEquals(listOf(LyricLine(1_000, "Verse")), restarted.get(track).lines)
        } finally { directory.deleteRecursively() }
    }
    @Test fun missingPrimaryLyricsFallBackAndSuccessfulResultsAreCached() = runBlocking {
        var primary = 0; var secondary = 0
        val repository = LyricsRepository(listOf(LyricsProvider { primary++; null }, LyricsProvider {
            secondary++; Lyrics(plain = "Complete verse", provider = "Second provider")
        }))
        assertEquals("Second provider", repository.get(track).provider)
        assertEquals("Complete verse", repository.get(track).plain)
        assertEquals(1, primary); assertEquals(1, secondary)
    }
    @Test fun primaryFailureStillUsesFallback() = runBlocking {
        val repository = LyricsRepository(listOf(LyricsProvider { throw IOException("unavailable") },
            LyricsProvider { Lyrics(lines = listOf(LyricLine(1000, "Verse")), provider = "Fallback") }))
        assertEquals("Fallback", repository.get(track).provider)
    }
    @Test fun transientFailuresAreNotNegativeCached() = runBlocking {
        var calls = 0
        val repository = LyricsRepository(listOf(LyricsProvider { calls++; throw IOException("offline") }))
        repeat(2) { try { repository.get(track); fail("Expected retryable error") } catch (_: IOException) { } }
        assertEquals(2, calls)
    }
    @Test fun cancellationDoesNotContactFallback() = runBlocking {
        var fallback = false
        val repository = LyricsRepository(listOf(LyricsProvider { throw CancellationException() },
            LyricsProvider { fallback = true; null }))
        try { repository.get(track); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertFalse(fallback)
    }
    @Test fun instrumentalDoesNotFallBackToAnUnrelatedVersion() = runBlocking {
        val repository = LyricsRepository(listOf(LyricsProvider { Lyrics(instrumental = true) }, LyricsProvider { error("Don't replace an instrumental") }))
        assertTrue(repository.get(track).instrumental)
    }
}
