package com.raagmusic.app

import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.RecommendationEngine
import com.raagmusic.app.data.youtube.YoutubeMusicRepository
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Live discovery latency and restart reuse; these timings do not score recommendation quality. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class DiscoveryLatencyProbeTest {
    @Test fun coldWarmRestartSearchRadioAndHome() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val directory = File(app.cacheDir, "discovery-latency-probe").apply { deleteRecursively(); mkdirs() }
        val snapshot = File(app.cacheDir, "home-discovery.json")
        val snapshotBytes = snapshot.takeIf { it.isFile }?.readBytes()
        val library = app.library.state.first(); val features = app.features.state.first()
        val models = ViewModelStore()
        var vm: MusicViewModel? = null
        suspend fun <T> timed(name: String, block: suspend () -> T): T {
            val at = System.nanoTime()
            return withTimeout(30_000) { block() }.also { Log.i("RaagDiscoveryProbe", "RESULT $name ms=${(System.nanoTime()-at)/1_000_000}") }
        }
        try {
            val repository = YoutubeMusicRepository(initialCountry = "IN", metadataDirectory = directory)
            timed("search_cold") { Assert.assertTrue(repository.searchPage("Daft Punk Get Lucky", "Songs", foreground = true).tracks.isNotEmpty()) }
            timed("search_warm") { repository.searchPage("Daft Punk Get Lucky", "Songs", foreground = true) }
            timed("suggestions_cold") { repository.searchSuggestions("daft punk") }
            timed("suggestions_warm") { repository.searchSuggestions("daft punk") }
            timed("radio_candidates_cold") { Assert.assertTrue(repository.getRelatedSongs("5NV6Rdv1a3I").isNotEmpty()) }
            val restarted = YoutubeMusicRepository(initialCountry = "IN", metadataDirectory = directory)
            timed("suggestions_restart") { restarted.searchSuggestions("daft punk") }
            timed("radio_candidates_restart") { Assert.assertTrue(restarted.getRelatedSongs("5NV6Rdv1a3I").isNotEmpty()) }
            val seed = Track("5NV6Rdv1a3I", "Get Lucky", "Daft Punk", "", "5NV6Rdv1a3I", Source.YOUTUBE, durationSeconds = 369)
            timed("recommendation_batch") { Assert.assertTrue(RecommendationEngine(restarted).next(seed, LibraryState(), FeatureState()).isNotEmpty()) }
            snapshot.delete()
            app.library.update { LibraryState(savedTracks = listOf(seed), favorites = setOf(seed.id)) }
            app.features.update { FeatureState(preferences = ListeningPreferences(rememberQueue = false), discovery = DiscoveryPreferences(countryOverride = "IN")) }
            val start = System.nanoTime()
            withContext(Dispatchers.Main) { vm = MusicViewModel(app, restarted); models.put("discovery-probe", vm!!) }
            withTimeout(30_000) { while (vm!!.homeSongs.value.items.isEmpty()) delay(10) }
            Log.i("RaagDiscoveryProbe", "RESULT home_first_content ms=${(System.nanoTime()-start)/1_000_000}")
            withTimeout(30_000) { while (vm!!.homeSongs.value.loading) delay(10) }
            Assert.assertTrue(vm!!.homeSongs.value.items.isNotEmpty())
            Log.i("RaagDiscoveryProbe", "RESULT home_complete ms=${(System.nanoTime()-start)/1_000_000}")
            timed("home_refresh") {
                withContext(Dispatchers.Main) { vm!!.loadHome(refresh = true) }
                withTimeout(30_000) { while (vm!!.homeRefreshing.value) delay(10) }
            }
        } finally {
            withContext(Dispatchers.Main) { models.clear() }
            app.library.update { library }; app.features.update { features }
            if (snapshotBytes == null) snapshot.delete() else snapshot.writeBytes(snapshotBytes)
            directory.deleteRecursively()
        }
    }
}
