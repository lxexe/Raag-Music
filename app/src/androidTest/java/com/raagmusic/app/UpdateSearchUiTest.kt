package com.raagmusic.app

import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.data.youtube.Track
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.*
import com.raagmusic.app.updates.GitHubRelease
import com.raagmusic.app.updates.UpdateState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class UpdateSearchUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RaagApplication
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun waitFor(text: String) = requireNotNull(device.wait(Until.findObject(By.text(text)), 8_000)) { "Missing UI: $text" }

    @Test fun updateDialogButtonsFitAndScheduleDetailsAreAbsentInLightAndDark() = runBlocking {
        val release = GitHubRelease("9.0.0", "## What's new\n- Cleaner search\n- Better update alerts", "https://github.com/lxexe/Raag-Music/releases/tag/v9.0.0", "https://github.com/lxexe/Raag-Music/releases/download/v9.0.0/Raag-Music-9.0.0.apk")
        val original = app.features.state.first()
        fun nativeTheme(mode: ThemeMode) {
            if (android.os.Build.VERSION.SDK_INT >= 31) instrumentation.runOnMainSync {
                app.getSystemService(android.app.UiModeManager::class.java).setApplicationNightMode(when (mode) {
                    ThemeMode.SYSTEM -> android.app.UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO
                    else -> android.app.UiModeManager.MODE_NIGHT_YES
                })
            }
        }
        try {
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.AMOLED)) {
                app.features.update { it.copy(theme = ThemePreferences(mode)) }
                nativeTheme(mode)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        RaagTheme(ThemePreferences(mode)) { AppUpdateDialog(UpdateState(release = release, lastChecked = 1000), {}, {}) }
                    } }
                    val update = waitFor("Update app")
                    val later = waitFor("Maybe later")
                    assertTrue(update.visibleBounds.bottom < device.displayHeight)
                    assertTrue(later.visibleBounds.bottom < device.displayHeight)
                    assertEquals(1, device.findObjects(By.text("What's new")).size)
                    assertFalse(device.hasObject(By.textContains("when online")))
                    assertFalse(device.hasObject(By.textContains("Android chooses")))
                    assertFalse(device.hasObject(By.textContains("Last checked")))
                    device.takeScreenshot(File(app.cacheDir, "updates-${mode.name.lowercase()}.png"))
                }
            }
        } finally {
            app.features.update { original }
            nativeTheme(original.theme.mode)
        }
    }

    @Test fun artistCategoryLoadsContinuationWithoutMixingSongsOrAlbums() {
        val pages = AtomicInteger()
        val artists = (1..7).map { ArtistProfile("Fixture artist $it", channelUrl = "https://www.youtube.com/channel/fixture-$it") }
        val songs = (1..8).map { Track("fixture%04d".format(it), "Fixture song $it", "Fixture artist", "", 120) }
        val engine = object : FixtureEngine() {
            override fun searchPage(query: String, kind: String, cursor: SearchCursor?): SearchPage {
                if (query != "fixture") return SearchPage()
                return if (kind == "Artists") {
                    pages.incrementAndGet()
                    if (cursor == null) SearchPage(artists = artists.take(2), next = SearchCursor(Page("https://example.com/page2"),
                        ServiceList.YouTube.getSearchExtractor("fixture", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")))
                    else SearchPage(artists = artists.drop(2))
                } else SearchPage(tracks = songs, artists = artists)
            }
        }
        val store = ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = MusicViewModel(app, YoutubeMusicRepository(engine))
                    store.put("fixture", model)
                    model.search("fixture")
                    activity.setContent { RaagTheme(ThemePreferences(ThemeMode.LIGHT)) { MusicSearchScreen(model, {}) } }
                }
                waitFor("Fixture song 1")
                assertFalse(device.hasObject(By.text("Fixture song 5")))
                device.takeScreenshot(File(app.cacheDir, "search-all.png"))
                waitFor("Artists").click()
                waitFor("Fixture artist 1")
                assertFalse(device.hasObject(By.textContains("Fixture song")))
                assertTrue(device.wait(Until.hasObject(By.text("Fixture artist 7")), 8_000))
                assertEquals(2, pages.get())
                device.takeScreenshot(File(app.cacheDir, "search-artists.png"))
            }
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun finalSettingsActionHasBottomClearance() {
        val store = ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val model = MusicViewModel(app, YoutubeMusicRepository(FixtureEngine()))
                    store.put("fixture", model)
                    activity.setContent { RaagTheme(ThemePreferences(ThemeMode.LIGHT)) {
                        ExpandableSettingsSheet({}, "Settings") { ListeningSettings(model, {}, {}) }
                    } }
                }
                waitFor("General")
                repeat(4) { device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 4, 25) }
                val about = waitFor("About Raag Music")
                assertTrue(about.visibleBounds.bottom < device.displayHeight - 80)
                device.takeScreenshot(File(app.cacheDir, "settings-bottom.png"))
            }
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun morningEveningJobsReplaceTheLegacyDailySchedule() {
        com.raagmusic.app.updates.UpdateSchedule.ensure(app, reset = true)
        val scheduler = app.getSystemService(android.app.job.JobScheduler::class.java)
        assertNull(scheduler.getPendingJob(72041))
        for (id in listOf(72042, 72043)) {
            val job = requireNotNull(scheduler.getPendingJob(id))
            assertFalse(job.isPeriodic)
            assertTrue(job.isPersisted)
            assertTrue(job.minLatencyMillis in 0..com.raagmusic.app.updates.DAY_MS)
        }
    }

    private open class FixtureEngine : ExtractionEngine {
        override fun audioStreamUrl(trackId: String): String = error("Playback is not part of UI tests")
        override fun searchSongs(query: String) = emptyList<Track>()
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<Track>()
    }
}
