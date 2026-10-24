package com.raagmusic.app

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.raagmusic.app.data.youtube.ArtistProfile
import com.raagmusic.app.domain.*
import com.raagmusic.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/** Opt-in visual/frame-timing probe. Emulator timings are not a physical-device guarantee. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ArtworkRenderingProbeTest {
    @Test fun captureArtworkFadesAndAmbientFrameCadence() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("profileArtwork") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val oldFeatures = app.features.state.first(); val oldLibrary = app.library.state.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val animationScale = android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)?.toFloatOrNull()
        val audio = File(app.cacheDir, "artwork-probe.wav")
        val count = 8000 * 2 * 30
        audio.writeBytes(ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count)
        }.array())
        val first = Track("artwork-probe-a", "Into the light", "Artist preview", "asset://cover_0.jpg", audio.toURI().toString(), Source.LOCAL)
        val second = first.copy(id = "artwork-probe-b", title = "Another color", artwork = "asset://cover_3.jpg")
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MusicViewModel? = null
        var measuredWindow: Window? = null
        val timingThread = HandlerThread("ArtworkFrameProbe").apply { start() }
        val durations = Collections.synchronizedList(mutableListOf<Long>())
        val vsyncs = Collections.synchronizedList(mutableListOf<Long>())
        val listener = Window.OnFrameMetricsAvailableListener { _, frame, _ ->
            durations += frame.getMetric(FrameMetrics.TOTAL_DURATION)
            vsyncs += frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        }
        fun refreshUi() { if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache() }
        fun screenshot(name: String) { refreshUi(); device.takeScreenshot(File(app.getExternalFilesDir(null), "scrim-$name.png")) }
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            app.features.update { FeatureState(profile = ListenerProfile(namePromptSeen = true),
                preferences = ListeningPreferences(analyticsEnabled = false), theme = ThemePreferences(mode = ThemeMode.AMOLED, playerStyle = PlayerStyle.APPLE)) }
            app.library.update { LibraryState(savedTracks = listOf(first, second)) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                vm = ViewModelProvider(it)[MusicViewModel::class.java]
                vm!!.pauseHomeLoading(); vm!!.play(first, listOf(first, second), ordered = true, recommendationChain = false); vm!!.player.pause()
            }
            device.wait(Until.findObject(By.descContains("Open full player")), 8000)!!.click()
            assertTrue(device.wait(Until.hasObject(By.desc("Close player")), 5000))
            delay(1800)
            screenshot("player-apple")
            val footerBottom = device.findObject(By.desc("Show lyrics")).visibleBounds.bottom
            val transportBottom = device.findObject(By.desc("Next")).visibleBounds.bottom
            assertTrue("Player footer stays at the safe bottom edge", device.displayHeight - footerBottom < device.displayHeight * .08)
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(timingThread.looper)); measuredWindow = it.window }
            delay(8000)
            scenario.onActivity { measuredWindow?.removeOnFrameMetricsAvailableListener(listener); measuredWindow = null }
            val sorted = durations.toList().sorted()
            val timestamps = vsyncs.toList().distinct().sorted()
            assertTrue("Expected animated ambient frames", sorted.size > 100)
            val seconds = (timestamps.last() - timestamps.first()) / 1_000_000_000.0
            fun percentile(fraction: Double) = sorted[((sorted.lastIndex * fraction).toInt())] / 1_000_000.0
            val report = "frames=${sorted.size} cadenceFps=${(timestamps.size - 1) / seconds} medianMs=${percentile(.5)} p95Ms=${percentile(.95)} p99Ms=${percentile(.99)}"
            File(app.getExternalFilesDir(null), "scrim-frame-timings.txt").writeText(report)
            android.util.Log.i("ArtworkFrameProbe", report)
            device.findObject(By.desc("Show playing next")).click(); delay(400); screenshot("playing-next")
            assertTrue(device.hasObject(By.text("Playing next")))
            device.findObject(By.desc("Hide playing next")).click()
            scenario.onActivity { vm!!.play(second, listOf(first, second), ordered = true, recommendationChain = false); vm!!.player.pause() }
            delay(1600); screenshot("track-change")
            assertEquals("Track changes must not move transport controls", transportBottom, device.findObject(By.desc("Next")).visibleBounds.bottom)
            scenario.onActivity { vm!!.updateTheme { it.copy(playerStyle = PlayerStyle.COMPACT) } }
            delay(600); screenshot("player-default")
            assertEquals("Artwork style must not move the footer", footerBottom, device.findObject(By.desc("Show lyrics")).visibleBounds.bottom)
            assertEquals("Artwork style must not move transport controls", transportBottom, device.findObject(By.desc("Next")).visibleBounds.bottom)
            scenario.onActivity {
                MusicViewModel::class.java.getDeclaredField("lyricsJob").apply { isAccessible = true }.get(vm).let { (it as? Job)?.cancel() }
                @Suppress("UNCHECKED_CAST")
                val lyrics = MusicViewModel::class.java.getDeclaredField("mutableLyrics").apply { isAccessible = true }.get(vm) as MutableStateFlow<LyricsState>
                lyrics.value = LyricsState(second.id, lyrics = Lyrics((0..25).map { LyricLine(it * 2000L, "A verse of the song, line ${it + 1}") }, provider = "Fixture"))
            }
            device.findObject(By.desc("Show lyrics")).click(); delay(500); screenshot("lyrics-inline")
            assertTrue("Inline lyrics keep transport visible", device.hasObject(By.desc("Next")))
            device.swipe(device.displayWidth / 2, device.displayHeight * 2 / 5,
                device.displayWidth / 2, device.displayHeight * 7 / 30, 24)
            delay(500); screenshot("lyrics-after-swipe")
            assertTrue("Reading lyrics expands the view", device.wait(Until.hasObject(By.desc("Exit full-screen lyrics")), 3000))
            delay(300); screenshot("lyrics-fullscreen")
            device.pressBack(); delay(250); refreshUi()
            device.findObject(By.desc("Back to player")).click(); delay(250); refreshUi()
            device.setOrientationLeft(); delay(1400); screenshot("player-landscape")
            assertTrue("Landscape keeps the transport visible", device.hasObject(By.desc("Next")))
            assertTrue("Landscape lyrics text must not be clipped",
                device.findObject(By.desc("Show lyrics")).visibleBounds.height() > device.displayHeight * .03)
            assertTrue("Landscape footer stays at the safe bottom edge",
                device.displayHeight - device.findObject(By.desc("Show lyrics")).visibleBounds.bottom < device.displayHeight * .12)
            device.setOrientationNatural(); delay(1200); refreshUi()
            assertEquals("Rotation restores the bottom dock position", footerBottom, device.findObject(By.desc("Show lyrics")).visibleBounds.bottom)
            device.findObject(By.desc("Close player")).click(); delay(400)
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.AMOLED)) {
                app.features.update { it.copy(theme = it.theme.copy(mode = mode)) }
                scenario.onActivity {
                    @Suppress("UNCHECKED_CAST")
                    val artist = MusicViewModel::class.java.getDeclaredField("mutableArtist").apply { isAccessible = true }.get(vm) as MutableStateFlow<ArtistPageState?>
                    artist.value = ArtistPageState("Artist preview", imageUrl = "asset://cover_0.jpg",
                        profile = ArtistProfile("Artist preview", description = "Artist header rendering preview."),
                        songs = ContentState(listOf(first, second)), identityBound = true)
                }
                delay(1000); screenshot("artist-${mode.name.lowercase()}")
                assertEquals("Only one artist back action", 1, device.findObjects(By.desc("Back from artist")).size)
                assertFalse(device.hasObject(By.desc("Close artist")))
                assertEquals("No duplicate artist title toolbar", 1, device.findObjects(By.text("Artist preview"))
                    .count { it.visibleBounds.top < device.displayHeight * .46 })
            }
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation()
            scenario?.onActivity { measuredWindow?.let { window -> runCatching { window.removeOnFrameMetricsAvailableListener(listener) } }; measuredWindow = null; vm?.player?.pause(); vm?.playback?.value?.queueIds?.indices?.reversed()?.forEach { index -> vm?.player?.removeQueue(index) } }
            timingThread.quitSafely(); scenario?.close(); delay(250)
            app.library.update { oldLibrary }; app.features.update { oldFeatures }; audio.delete()
            device.executeShellCommand(if (animationScale == null) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $animationScale")
        }
        Unit
    }
}
