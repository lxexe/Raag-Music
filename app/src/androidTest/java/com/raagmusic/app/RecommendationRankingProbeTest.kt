package com.raagmusic.app

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RecommendationRankingProbeTest {
    @Test fun rankLargeLibraryWithListeningHistory() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("profilePlayback") == "true")
        val day = LocalDate.of(2026, 10, 18)
        val songs = (0 until 1_000).map { Track("song$it", "Song $it", "Artist ${it % 100}", "", "fixture", durationSeconds = 180, genre = "Genre ${it % 8}", mood = "Mood ${it % 4}") }
        val library = LibraryState(savedTracks = songs, favorites = songs.filterIndexed { i, _ -> i % 5 == 0 }.map { it.id }.toSet())
        val features = FeatureState(history = songs.flatMapIndexed { i, track -> (0 until 20).map { ago ->
            ListenRecord(track.id, day.minusDays(ago.toLong()).toString(), 120_000, 1, if (i % 11 == 0) 1 else 0)
        } })
        withContext(Dispatchers.Default) {
            repeat(3) { rankRecommendations(songs, songs.first(), library, features, day) }
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0
            val timings = (0 until 5).map {
                val at = System.nanoTime()
                val ranked = rankRecommendations(songs, songs.first(), library, features, day)
                Assert.assertEquals(20, ranked.size)
                (System.nanoTime() - at) / 1_000_000
            }.sorted()
            Log.i("RaagRankingProbe", "RESULT candidates=${songs.size} history=${features.history.size} medianMs=${timings[2]} samplesMs=$timings allocatedBytes=${(Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0)-allocated}")
        }
        Unit
    }
}
