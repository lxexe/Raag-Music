package com.raagmusic.app

import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AnalyticsExperienceTest {
    @Test fun analyticsFiltersRecentHistoryPrivacyAndBothPlayerStylesWorkTogether() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val oldFeatures = app.features.state.first(); val oldLibrary = app.library.state.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val audio = File(app.cacheDir, "analytics-ui.wav")
        val count = 8000 * 2 * 60
        val buffer = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count)
        audio.writeBytes(buffer.array())
        val song = Track("analytics-ui", "My recent song", "My artist", "asset://cover_0.jpg", audio.toURI().toString(), Source.LOCAL)
        val today = LocalDate.now()
        val history = listOf(ListenRecord(song.id, today.toString(), 180_000, 5, 1), ListenRecord(song.id, today.minusDays(6).toString(), 120_000, 2))
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MusicViewModel? = null
        suspend fun click(selector: BySelector) {
            delay(500)
            if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val target = device.wait(Until.findObject(selector), 8_000)
            if (target == null) device.takeScreenshot(File(app.getExternalFilesDir(null), "insights-failure.png"))
            assertNotNull("Expected visible control: $selector", target)
            target!!.click(); device.waitForIdle(1000)
        }
        suspend fun eventually(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(50) }
        try {
            app.features.update { FeatureState(profile = ListenerProfile(namePromptSeen = true), theme = ThemePreferences(mode = ThemeMode.LIGHT), history = history) }
            app.library.update { LibraryState(savedTracks = listOf(song), recentIds = listOf(song.id)) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[MusicViewModel::class.java] }
            assertFalse("Analytics is no longer a bottom destination", device.hasObject(By.desc("Analytics")))
            click(By.desc("Library"))
            val tabs = device.wait(Until.findObject(By.text("Playlists")), 5000)!!.visibleBounds
            device.swipe(device.displayWidth * 4 / 5, tabs.centerY(), device.displayWidth / 5, tabs.centerY(), 24)
            click(By.text("Insights"))
            assertTrue(device.wait(Until.hasObject(By.text("Your listening")), 5000))
            click(By.text("1d")); assertTrue(device.wait(Until.hasObject(By.text("3m")), 5000))
            click(By.text("7d")); assertTrue(device.wait(Until.hasObject(By.text("5m")), 5000))
            device.takeScreenshot(File(app.getExternalFilesDir(null), "analytics-light.png"))
            scenario.onActivity { vm!!.updatePreferences { it.copy(analyticsEnabled = false) } }
            assertTrue(device.wait(Until.hasObject(By.text("Analytics are paused")), 5000))
            scenario.onActivity { vm!!.play(song, listOf(song), ordered = true, recommendationChain = false) }
            eventually { vm!!.playback.value.playing }
            delay(1500)
            assertEquals(history, app.features.state.first().history)
            assertEquals(song.id, app.library.state.first().recentIds.first())
            click(By.descContains("Open full player"))
            for (style in listOf(PlayerStyle.COMPACT, PlayerStyle.APPLE)) {
                scenario.onActivity { vm!!.updateTheme { it.copy(mode = ThemeMode.AMOLED, playerStyle = style) } }
                delay(700)
                assertTrue(device.hasObject(By.desc("Close player")))
                device.takeScreenshot(File(app.getExternalFilesDir(null), "player-${style.name.lowercase()}.png"))
            }
            click(By.desc("Close player"))
            click(By.desc("Insights settings"))
            assertTrue(device.wait(Until.hasObject(By.text("Settings")), 5000))
            device.takeScreenshot(File(app.getExternalFilesDir(null), "settings-amoled.png"))
        } finally {
            scenario?.onActivity { vm?.player?.pause(); vm?.playback?.value?.queueIds?.indices?.reversed()?.forEach { vm?.player?.removeQueue(it) } }
            scenario?.close(); delay(300)
            app.library.update { oldLibrary }; app.features.update { oldFeatures }; audio.delete()
        }
        Unit
    }
}
