package com.raagmusic.app

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.PlaybackService
import com.raagmusic.app.ui.MusicViewModel
import com.raagmusic.app.ui.ContentState
import com.raagmusic.app.ui.LyricsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExperienceRefinementTest {
    @Test fun settingsAlwaysOpenFullScreenWithOneHeader() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val original = app.features.state.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            app.features.update { it.copy(profile = ListenerProfile(namePromptSeen = true), preferences = it.preferences.copy(rememberQueue = false), queue = QueueSnapshot()) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            device.wait(Until.findObject(By.desc("Settings")), 8_000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("Close settings")), 5_000))
            assertTrue(device.findObject(By.text("Settings")).visibleBounds.top < device.displayHeight / 4)
            assertFalse(device.hasObject(By.desc("Full-screen settings")))
            assertTrue(device.hasObject(By.text("General")))
            device.findObject(By.text("Appearance")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("Close appearance")), 5_000))
            assertTrue(device.findObject(By.text("Appearance")).visibleBounds.top < device.displayHeight / 4)
            device.findObject(By.text("App theme")).click()
            assertTrue(device.wait(Until.hasObject(By.text("AMOLED")), 5_000))
            assertTrue(device.hasObject(By.text("Light"))); assertTrue(device.hasObject(By.text("System")))
            assertFalse(device.hasObject(By.text("Dark"))); assertFalse(device.hasObject(By.text("Dynamic")))
            device.pressBack()
            device.takeScreenshot(File(app.getExternalFilesDir(null), "full-screen-appearance.png"))
        } finally { scenario?.close(); app.features.update { original } }
        Unit
    }

    @Test fun youtubeStylePicksDirectArtistsFullscreenSettingsAndAllPlayerStyles() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val originalLibrary = app.library.state.first()
        val originalFeatures = app.features.state.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val audio = File(app.cacheDir, "experience-refinement.wav")
        val count = 8_000 * 2 * 60
        val bytes = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count)
        audio.writeBytes(bytes.array())
        val tracks = (0 until 20).map { Track("grid-$it", "Grid song $it", "Singer A, Singer B", "asset://cover_${it % 6}.jpg", audio.toURI().toString(), durationSeconds = 60) }
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MusicViewModel? = null
        suspend fun click(selector: BySelector) {
            delay(350)
            val target = device.wait(Until.findObject(selector), 8_000)
            assertNotNull("Expected $selector", target)
            if (selector.toString().contains("Open full player")) {
                val bounds = target.visibleBounds
                device.click(bounds.left + bounds.width() / 4, bounds.top + bounds.height() / 3)
                assertTrue("Player expands after tapping the mini player", device.wait(Until.hasObject(By.desc("Close player")), 5_000))
            } else target.click()
            device.waitForIdle(1_000)
        }
        suspend fun screenshot(name: String) {
            delay(600)
            device.takeScreenshot(File(app.getExternalFilesDir(null), "refinement-$name.png"))
        }
        try {
            app.stopService(Intent(app, PlaybackService::class.java)); delay(300)
            app.library.update { LibraryState(savedTracks = tracks) }
            app.features.update { FeatureState(profile = ListenerProfile(namePromptSeen = true), theme = ThemePreferences(mode = ThemeMode.DYNAMIC),
                preferences = ListeningPreferences(collectHistory = false), queue = QueueSnapshot(tracks.take(3))) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[MusicViewModel::class.java]; vm!!.play(tracks.first(), tracks.take(3), ordered = true, recommendationChain = false); vm!!.player.pause() }
            withTimeout(12_000) { while (vm!!.playback.value.queueIds.size != 3) delay(50) }
            scenario.onActivity {
                @Suppress("UNCHECKED_CAST")
                val home = MusicViewModel::class.java.getDeclaredField("mutableHomeSongs").apply { isAccessible = true }.get(vm) as MutableStateFlow<ContentState<Track>>
                home.value = ContentState(tracks)
            }
            assertTrue(device.wait(Until.hasObject(By.text("Quick picks")), 8_000))
            withTimeout(10_000) { while (vm!!.quickPicks.value.items.size != 20) delay(50) }
            delay(800)
            screenshot("home")
            val tiles = device.findObjects(By.descContains("Artwork for Grid song"))
                .mapNotNull { runCatching { it.visibleBounds }.getOrNull() }.filter { it.top < device.displayHeight * 3 / 4 }.sortedWith(compareBy({ it.top }, { it.left }))
            val firstColumn = tiles.filter { it.left == tiles.minOf { tile -> tile.left } }.sortedBy { it.top }
            assertEquals("Four vertically stacked rows per page: $tiles", 4, firstColumn.size)
            assertTrue("A next page peeks into view", tiles.any { it.left > firstColumn.first().left })
            assertTrue("Mood chips at the top", device.hasObject(By.text("Romance")))
            assertTrue("Quick picks offer Play all", device.hasObject(By.text("Play all")))
            click(By.desc("Next quick picks page"))
            assertTrue(device.hasObject(By.desc("Previous quick picks page")))
            click(By.desc("Previous quick picks page"))
            screenshot("home")
            app.features.update { it.copy(experience = it.experience.copy(homeMoods = listOf("For you", "Romance"))) }
            click(By.desc("Add mood chip"))
            val moodName = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
            assertNotNull(moodName); moodName.text = "Road trip"
            click(By.text("Add"))
            withTimeout(5_000) { app.features.state.first { "Road trip" in it.experience.homeMoods } }
            assertTrue(device.wait(Until.hasObject(By.text("Road trip")), 5_000))
            screenshot("custom-chips")
            click(By.desc("Customize Home")); click(By.desc("Remove Road trip chip"))
            withTimeout(5_000) { app.features.state.first { "Road trip" !in it.experience.homeMoods } }
            device.pressBack()
            app.features.update { it.copy(experience = it.experience.copy(homeMoods = defaultHomeMoods)) }
            scenario.onActivity { vm!!.play(tracks.first(), tracks.take(3), ordered = true, recommendationChain = false); vm!!.player.pause() }
            click(By.descContains("Open full player"))
            assertFalse(device.hasObject(By.textContains("Swipe down to minimize")))
            screenshot("immersive")
            click(By.text("Singer B"))
            withTimeout(2_000) { while (vm!!.artist.value?.name != "Singer B") delay(10) }
            device.pressBack()
            click(By.descContains("Open full player"))
            for (style in PlayerStyle.entries.filter { it != PlayerStyle.IMMERSIVE }) {
                app.features.update { it.copy(theme = it.theme.copy(playerStyle = style)) }
                delay(700)
                assertTrue("Player controls for $style", device.hasObject(By.desc("Close player")))
                screenshot(style.name.lowercase())
            }
            scenario.onActivity {
                @Suppress("UNCHECKED_CAST")
                val lyrics = MusicViewModel::class.java.getDeclaredField("mutableLyrics").apply { isAccessible = true }.get(vm) as MutableStateFlow<LyricsState>
                lyrics.value = LyricsState(tracks.first().id, lyrics = Lyrics(lines = listOf(
                    LyricLine(0, "First characters and the final characters stay visible when this long verse wraps across several lines."),
                    LyricLine(10_000, "Another verse with enough room to breathe.")), provider = "Test fixture"))
            }
            click(By.desc("Full-screen lyrics"))
            assertTrue(device.wait(Until.hasObject(By.desc("Back to player")), 5_000))
            screenshot("lyrics")
            click(By.desc("Back to player"))
            click(By.desc("Song options"))
            click(By.text("Open queue"))
            assertTrue(device.wait(Until.hasObject(By.text("Up next")), 5_000))
            assertTrue(device.hasObject(By.text("Discover")))
            assertTrue(device.hasObject(By.desc("Queue settings")))
            screenshot("queue")
            click(By.desc("Close queue")); click(By.desc("Close player"))
            click(By.desc("Settings"))
            assertTrue(device.wait(Until.hasObject(By.desc("Close settings")), 5_000))
            screenshot("settings")
            val settingsScroll = device.findObject(By.scrollable(true))
            repeat(12) { if (!device.hasObject(By.text("Appearance"))) settingsScroll.scroll(Direction.UP, .8f) }
            click(By.text("Appearance"))
            assertTrue(device.wait(Until.hasObject(By.text("Player & lyrics")), 5_000))
            screenshot("accents")
        } finally {
            scenario?.onActivity { vm?.player?.pause(); vm?.playback?.value?.queueIds?.indices?.reversed()?.forEach { vm?.player?.removeQueue(it) } }
            scenario?.close(); delay(300)
            app.stopService(Intent(app, PlaybackService::class.java)); delay(300)
            app.library.update { originalLibrary }; app.features.update { originalFeatures }; audio.delete()
        }
    }
}
