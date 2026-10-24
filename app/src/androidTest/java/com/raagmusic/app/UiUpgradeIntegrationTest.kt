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
class UiUpgradeIntegrationTest {
    @Test fun fullPlayerQueueRemovalUndoAndNestedBackWorkThroughVisibleControls() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val originalLibrary = app.library.state.first()
        val originalFeatures = app.features.state.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val file = File(app.cacheDir, "ui-upgrade-fixture.wav")
        val count = 8000 * 2 * 60
        val bytes = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count)
        file.writeBytes(bytes.array())
        val a = Track("ui-fixture-a", "Fixture one", "Artist", "asset://cover_0.jpg", file.toURI().toString())
        val b = a.copy(id = "ui-fixture-b", title = "Fixture two")
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MusicViewModel? = null
        suspend fun awaitState(label: String, predicate: () -> Boolean) {
            try { withTimeout(15_000) { while (!predicate()) delay(50) } }
            catch (e: TimeoutCancellationException) { throw AssertionError("$label: ${vm?.playback?.value}", e) }
        }
        suspend fun click(selector: BySelector) {
            delay(500) // Compose transitions can expose controls before their final hit bounds settle.
            val target = device.wait(Until.findObject(selector), 8000)
            if (target == null) device.takeScreenshot(File(app.getExternalFilesDir(null), "ui-refresh-failure.png"))
            assertNotNull("Expected visible control: $selector, queue: ${vm?.playback?.value?.queueIds}", target)
            target.click(); device.waitForIdle(1000)
        }
        try {
            app.stopService(Intent(app, PlaybackService::class.java)); delay(300)
            app.library.update { LibraryState(savedTracks = listOf(a, b)) }
            app.features.update { FeatureState(profile = ListenerProfile(namePromptSeen = true), theme = ThemePreferences(mode = ThemeMode.AMOLED, playerStyle = PlayerStyle.COMPACT),
                preferences = ListeningPreferences(collectHistory = false), queue = QueueSnapshot(listOf(a, b))) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                vm = ViewModelProvider(it)[MusicViewModel::class.java]
                // Replace any service session still bound by an earlier instrumented test.
                vm!!.player.play(a, listOf(a, b), ordered = true)
                vm!!.player.pause()
            }
            awaitState("fixture queue") { vm!!.playback.value.queueIds == listOf(a.id, b.id) }
            scenario.onActivity { vm!!.player.pause() }
            awaitState("fixture paused") { !vm!!.playback.value.playWhenReady }
            assertTrue(device.wait(Until.hasObject(By.desc("Previous track")), 5000))
            assertTrue(device.hasObject(By.desc("Next track")))
            assertFalse(device.hasObject(By.desc("Add favorite")))
            assertFalse(device.hasObject(By.desc("Remove favorite")))
            device.takeScreenshot(File(app.getExternalFilesDir(null), "home-player-1.17.png"))
            click(By.descContains("Open full player"))
            assertTrue(device.wait(Until.hasObject(By.desc("Add favorite")), 5000))
            device.takeScreenshot(File(app.getExternalFilesDir(null), "full-player-1.17.png"))
            val playerScreenshot = File(app.getExternalFilesDir(null), "ui-refresh-player.png")
            device.takeScreenshot(playerScreenshot)
            val bitmap = android.graphics.BitmapFactory.decodeFile(playerScreenshot.path)
            assertTrue("Player atmosphere remains dark enough for white controls", androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, bitmap.getPixel(8, bitmap.height / 2)) >= 4.5)
            bitmap.recycle()
            assertFalse("Up next starts below the player viewport", device.hasObject(By.text("Up next")))
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 24)
            assertTrue(device.wait(Until.hasObject(By.text("Up next")), 5000))
            device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 24)
            device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 24)
            assertTrue(device.wait(Until.hasObject(By.descContains("Open full player")), 5000))
            val mini = device.findObject(By.descContains("Open full player"))
            device.swipe(mini.visibleCenter.x, mini.visibleCenter.y,
                mini.visibleCenter.x, mini.visibleCenter.y - device.displayHeight / 4, 24)
            assertTrue(device.wait(Until.hasObject(By.desc("Close player")), 5000))
            assertFalse(device.hasObject(By.text("Queue")))
            assertTrue(device.hasObject(By.desc("Download track")))
            click(By.desc("Song options"))
            assertFalse(device.hasObject(By.text("Song options")))
            assertFalse(device.hasObject(By.text("Show visualizer")))
            assertFalse(device.hasObject(By.text("Ambient mode")))
            assertFalse(device.hasObject(By.text("Share song card")))
            click(By.text("Open queue"))
            click(By.desc("Options for Fixture one"))
            click(By.text("Remove from queue"))
            awaitState("queue removal") { vm!!.playback.value.queueIds.size == 1 }
            click(By.text("Undo"))
            awaitState("queue Undo") { vm!!.playback.value.queueIds == listOf(a.id, b.id) }
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Save queue as playlist")), 5000))
            assertTrue(device.wait(Until.hasObject(By.desc("Close player")), 5000))
            click(By.desc("Show lyrics"))
            assertTrue(device.wait(Until.hasObject(By.desc("Back to player")), 5000))
            assertTrue("Inline lyrics preserve playback controls", device.hasObject(By.desc("Next")))
            click(By.desc("Full-screen lyrics"))
            assertTrue(device.wait(Until.hasObject(By.desc("Exit full-screen lyrics")), 5000))
            click(By.desc("Exit full-screen lyrics"))
            assertTrue(device.wait(Until.hasObject(By.desc("Full-screen lyrics")), 5000))
            click(By.desc("Back to player"))
            assertTrue(device.wait(Until.gone(By.desc("Back to player")), 5000))
            click(By.desc("Show lyrics"))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.desc("Back to player")), 5000))
            assertTrue(device.wait(Until.hasObject(By.desc("Close player")), 5000))
            click(By.desc("Close player"))
            assertTrue(device.wait(Until.hasObject(By.descContains("Open full player")), 5000))
        } finally {
            scenario?.onActivity { vm?.player?.pause(); vm?.playback?.value?.queueIds?.indices?.reversed()?.forEach { vm?.player?.removeQueue(it) } }
            scenario?.close(); delay(300)
            app.stopService(Intent(app, PlaybackService::class.java)); delay(300)
            app.library.update { originalLibrary }; app.features.update { originalFeatures }; file.delete()
        }
    }
}
