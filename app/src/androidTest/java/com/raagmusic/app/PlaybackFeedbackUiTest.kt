package com.raagmusic.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.raagmusic.app.data.youtube.CollectionKind
import com.raagmusic.app.data.youtube.BrowseCollection
import com.raagmusic.app.data.youtube.ExtractionEngine
import com.raagmusic.app.data.youtube.SearchCursor
import com.raagmusic.app.data.youtube.SearchPage
import com.raagmusic.app.data.youtube.YoutubeMusicRepository
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import com.raagmusic.app.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackFeedbackUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RaagApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun waitFor(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 8_000)) { "Missing UI: $text" }
    private fun nativeTheme(mode: ThemeMode) = instrumentation.runOnMainSync {
        app.getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(when (mode) {
            ThemeMode.SYSTEM -> android.app.UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO
            else -> android.app.UiModeManager.MODE_NIGHT_YES
        })
    }

    @Test fun failureActionsFitInLightAndAmoledAndCooldownDisablesRetry() = runBlocking {
        val original = app.features.state.first()
        val retries = AtomicInteger()
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.AMOLED)) {
                app.features.update { it.copy(theme = ThemePreferences(mode)) }; nativeTheme(mode)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        RaagTheme(ThemePreferences(mode)) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 48.dp)) {
                                PlaybackFailureCard(PlaybackFailure(PlaybackFailureKind.VERIFICATION,
                                    "YouTube needs verification", "Try another network or open this song in YouTube.",
                                    System.currentTimeMillis() + 86_400_000), // Long fixture deadline tolerates host sleep during instrumentation.
                                    Track("5NV6Rdv1a3I", "Fixture song", "Fixture artist", "", "5NV6Rdv1a3I", Source.YOUTUBE), { retries.incrementAndGet() })
                            }
                        }
                    } }
                    waitFor("YouTube needs verification")
                    val retry = requireNotNull(device.wait(Until.findObject(By.textStartsWith("Retry in")), 8_000))
                    retry.click() // Text child enabled state differs from the disabled Compose button.
                    val external = waitFor("Open in YouTube")
                    assertTrue(external.visibleBounds.right <= device.displayWidth - 16)
                    assertTrue(external.visibleBounds.bottom < device.displayHeight)
                    assertEquals(0, retries.get())
                    device.takeScreenshot(File(app.cacheDir, "playback-failure-${mode.name.lowercase()}.png"))
                }
            }
            app.features.update { it.copy(theme = original.theme) }; nativeTheme(original.theme.mode)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    RaagTheme(original.theme) { Column(Modifier.padding(40.dp)) {
                        PlaybackFailureCard(PlaybackFailure(PlaybackFailureKind.CONNECTION, "Connection interrupted", "Try again."),
                            Track("5NV6Rdv1a3I", "Song", "Artist", "", "5NV6Rdv1a3I", Source.YOUTUBE), { retries.incrementAndGet() })
                    } }
                } }
                waitFor("Try again").click()
                instrumentation.waitForIdleSync()
                assertEquals(1, retries.get())
            }
        } finally { app.features.update { original }; nativeTheme(original.theme.mode) }
    }

    @Test fun quickPicksRespondToLikesAndNotInterestedWithoutRefetching() = runBlocking {
        val originalFeatures = app.features.state.first()
        val originalLibrary = app.library.state.first()
        val store = ViewModelStore()
        val calls = AtomicInteger()
        val heard = Track("heard000001", "Known song", "Loved artist", "", "heard000001", Source.YOUTUBE, durationSeconds = 180)
        val match = heard.copy(id = "match000001", title = "Relevant new song")
        val generic = (1..30).map { Track("generic%04d".format(it), "Generic song $it", "Artist $it", "", "generic%04d".format(it), durationSeconds = 180) }
        val engine = object : ExtractionEngine {
            override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
            override fun collectionTracks(url: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
            override fun searchSongs(query: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
            override fun audioStreamUrl(trackId: String): String = error("No live playback in fixture test")
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage {
                calls.incrementAndGet()
                return SearchPage(tracks = (generic + match).map { com.raagmusic.app.data.youtube.Track(it.id, it.title, it.artist, "", 180) })
            }
        }
        try {
            app.features.update { FeatureState(theme = ThemePreferences(ThemeMode.LIGHT)) }; nativeTheme(ThemeMode.LIGHT)
            app.library.update { LibraryState(savedTracks = listOf(heard)) }
            lateinit var model: MusicViewModel
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    model = MusicViewModel(app, YoutubeMusicRepository(engine)); store.put("fixture", model)
                    activity.setContent { RaagTheme(ThemePreferences(ThemeMode.LIGHT)) {
                        val picks by model.quickPicks.collectAsState()
                        Column(Modifier.padding(40.dp)) {
                            Text(if (picks.loading) "Loading fixture" else "Fixture ready")
                            picks.items.take(4).forEach { Text(it.title) }
                        }
                    } }
                    model.loadHome(refresh = true)
                }
                waitFor("Fixture ready")
                val before = calls.get()
                app.library.update { it.copy(favorites = setOf(heard.id)) }
                waitFor("Relevant new song")
                instrumentation.runOnMainSync { model.lessLike(match) }
                assertTrue(device.wait(Until.gone(By.text("Relevant new song")), 8_000))
                assertTrue(app.features.state.first().lessLikeIds.contains(match.id))
                assertTrue(app.library.state.first().savedTracks.any { it.id == match.id })
                assertEquals("Feedback should rerank the retained pool without new searches", before, calls.get())
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            app.library.update { originalLibrary }; app.features.update { originalFeatures }; nativeTheme(originalFeatures.theme.mode)
        }
    }
}
