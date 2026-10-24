package com.raagmusic.app

import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.DeviceCountryResolver
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class HomeDiscoveryIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RaagApplication

    @Test fun deviceCountryCanBeResolvedWithoutLocationPermission() {
        val region = DeviceCountryResolver.detect(app)
        assertTrue(region.code == null || validCountryCode(region.code) == region.code)
        assertTrue(region.source in listOf(CountrySource.NETWORK, CountrySource.SIM, CountrySource.DEVICE, CountrySource.GLOBAL))
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION))
    }

    @Test fun recentSearchesAndCountryWritesPreservePlaybackAppearanceAndLibrary() = runBlocking {
        val original = app.features.state.first()
        val library = app.library.state.first()
        try {
            coroutineScope {
                launch { app.features.update { it.copy(discovery = it.discovery.withSearch("Daft Punk")) } }
                launch { app.features.update { it.copy(discovery = it.discovery.copy(countryOverride = "IN")) } }
            }
            val saved = app.features.state.first()
            assertEquals("IN", saved.discovery.countryOverride)
            assertEquals("Daft Punk", saved.discovery.recentSearches.first())
            assertEquals(original.queue, saved.queue)
            assertEquals(original.theme, saved.theme)
            assertEquals(original.history, saved.history)
            assertEquals(library, app.library.state.first())
        } finally { app.features.update { original } }
        Unit
    }

    @Test fun firstOpenUsesDetectedCountryBeforeLoadingContent() = runBlocking {
        val original = app.features.state.first()
        val store = ViewModelStore()
        lateinit var model: MusicViewModel
        try {
            app.features.update { it.copy(discovery = it.discovery.copy(countryOverride = null)) }
            instrumentation.runOnMainSync { model = MusicViewModel(app); store.put("home", model) }
            val detected = DeviceCountryResolver.detect(app)
            val region = withTimeout(15_000) { model.homeRegion.first { it != null } }
            assertEquals(detected, region)
            assertTrue(homeQueries(region!!, "For you", java.time.LocalDate.now()).playlists.contains(region.queryName))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.features.update { original }
        }
        Unit
    }

    @Test fun regionalHomeLoadsRealTrendingReleasesPlaylistsAndAlbums() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val original = app.features.state.first()
        val store = ViewModelStore()
        lateinit var model: MusicViewModel
        try {
            app.features.update { it.copy(discovery = it.discovery.copy(countryOverride = "IN")) }
            instrumentation.runOnMainSync { model = MusicViewModel(app); store.put("regional", model) }
            instrumentation.runOnMainSync { listOf("trending", "releases", "playlists", "albums").forEach(model::requestHomeSection) }
            withTimeout(120_000) { model.homeAlbums.first { !it.loading } }
            android.util.Log.i("RaagProviderSmoke", "Regional album search: ${model.homeAlbums.value.items.size}; error=${model.homeAlbums.value.error}")
            assertEquals(MusicRegion("IN", CountrySource.MANUAL), model.homeRegion.value)
            assertTrue(model.homeTrending.value.error.orEmpty(), model.homeTrending.value.items.isNotEmpty())
            assertTrue(model.homeSongs.value.error.orEmpty(), model.homeSongs.value.items.isNotEmpty())
            assertTrue(model.homeNewReleases.value.error.orEmpty(), model.homeNewReleases.value.items.isNotEmpty())
            assertTrue(model.homePlaylists.value.error.orEmpty(), model.homePlaylists.value.items.isNotEmpty())
            assertTrue(model.homeAlbums.value.error.orEmpty(), model.homeAlbums.value.items.isNotEmpty())
            // A public playlist may contain news or other nonmusic videos.
            val musicIds = app.youtube.searchTracks(homeQueries(MusicRegion("IN"), "For you", java.time.LocalDate.now()).trending)
                .first().map { it.id }.toSet()
            assertTrue(model.homeTrending.value.items.all { it.id in musicIds })
            assertFalse(model.homeRefreshing.value)
            android.util.Log.i("RaagProviderSmoke", "India home: trending=${model.homeTrending.value.items.size}; releases=${model.homeNewReleases.value.items.size}; playlists=${model.homePlaylists.value.items.size}; albums=${model.homeAlbums.value.items.size}")
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.features.update { original }
            app.youtube.setCountry(DeviceCountryResolver.detect(app).providerCode)
        }
        Unit
    }
}
