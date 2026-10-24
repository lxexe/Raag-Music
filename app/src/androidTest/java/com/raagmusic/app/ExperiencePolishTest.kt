package com.raagmusic.app

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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExperiencePolishTest {
    @Test fun lightLibraryArtistAndSongSwipesRemainReadableAndUsable() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val oldFeatures = app.features.state.first(); val oldLibrary = app.library.state.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val audio = File(app.cacheDir, "polish-fixture.wav")
        val count = 8000 * 2 * 60
        audio.writeBytes(ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count)
        }.array())
        val songs = (1..8).map { Track("polish-$it", "Polish song $it", "Preview artist", "asset://cover_${it % 4}.jpg", audio.toURI().toString(), Source.LOCAL, durationSeconds = 60) }
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MusicViewModel? = null
        suspend fun settle() { delay(600); if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache() }
        suspend fun click(selector: BySelector) {
            settle()
            assertNotNull("Expected $selector", device.wait(Until.findObject(selector), 6000))
            device.findObject(selector).click(); settle()
        }
        suspend fun screenshot(name: String) { settle(); device.takeScreenshot(File(app.getExternalFilesDir(null), "polish-$name.png")) }
        suspend fun eventually(block: () -> Boolean) = withTimeout(10_000) { while (!block()) delay(50) }
        try {
            device.wakeUp(); device.executeShellCommand("wm dismiss-keyguard")
            app.features.update { FeatureState(profile = ListenerProfile(namePromptSeen = true),
                theme = ThemePreferences(mode = ThemeMode.LIGHT, reduceMotion = true), preferences = ListeningPreferences(analyticsEnabled = false)) }
            app.library.update { LibraryState(savedTracks = songs, favorites = setOf(songs.first().id),
                playlists = listOf(Playlist("polish-mix", "Evening favorites", songs.take(4).map { it.id }))) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                vm = ViewModelProvider(it)[MusicViewModel::class.java]
                vm!!.pauseHomeLoading()
                MusicViewModel::class.java.getDeclaredField("homeInterrupted").apply { isAccessible = true }.setBoolean(vm, false)
                @Suppress("UNCHECKED_CAST")
                val home = MusicViewModel::class.java.getDeclaredField("mutableHomeSongs").apply { isAccessible = true }.get(vm) as MutableStateFlow<ContentState<Track>>
                home.value = ContentState(songs)
                vm!!.play(songs.first(), songs.take(3), ordered = true, recommendationChain = false)
                vm!!.player.pause()
            }
            eventually { vm!!.playback.value.queueIds.size == 3 }
            screenshot("home-light")
            assertFalse(device.hasObject(By.desc("Customize Home")))
            assertFalse(device.hasObject(By.textContains("music lover")))
            click(By.desc("Library")); screenshot("library-light")
            assertTrue(device.hasObject(By.text("Your library")))
            assertTrue(device.hasObject(By.text("Liked songs")))
            assertTrue(device.hasObject(By.text("Evening favorites")))
            click(By.desc("Search")); device.pressBack(); settle()
            assertFalse(device.hasObject(By.text("Start with a feeling")))
            assertFalse(device.hasObject(By.text("EXPLORE YOUR SOUND")))
            click(By.descContains("Open full player"))
            scenario.onActivity {
                (MusicViewModel::class.java.getDeclaredField("lyricsJob").apply { isAccessible = true }.get(vm) as? Job)?.cancel()
                @Suppress("UNCHECKED_CAST")
                val lyrics = MusicViewModel::class.java.getDeclaredField("mutableLyrics").apply { isAccessible = true }.get(vm) as MutableStateFlow<LyricsState>
                lyrics.value = LyricsState(songs.first().id)
            }
            settle()
            assertFalse(device.findObject(By.desc("Lyrics unavailable")).isEnabled)
            screenshot("player-light")
            val art = device.findObject(By.desc("Artwork for ${songs.first().title}"))
                ?: device.findObject(By.desc(songs.first().title))
            assertNotNull("Artwork is a swipe target", art)
            val bounds = art.visibleBounds
            // Stay inside the artwork; edge-to-edge drags are Android's system Back gesture.
            device.swipe(bounds.left + bounds.width() * 3 / 4, bounds.centerY(), bounds.left + bounds.width() / 4, bounds.centerY(), 24)
            eventually { vm!!.playback.value.trackId == songs[1].id }
            scenario.onActivity { vm!!.player.seek(8_000) }
            eventually { vm!!.playback.value.position >= 7_500 }
            settle()
            device.swipe(bounds.left + bounds.width() / 4, bounds.centerY(), bounds.left + bounds.width() * 3 / 4, bounds.centerY(), 24)
            eventually { vm!!.playback.value.trackId == songs.first().id }
            click(By.desc("Close player"))
            scenario.onActivity {
                @Suppress("UNCHECKED_CAST")
                val artist = MusicViewModel::class.java.getDeclaredField("mutableArtist").apply { isAccessible = true }.get(vm) as MutableStateFlow<ArtistPageState?>
                artist.value = ArtistPageState("Preview artist", imageUrl = "asset://cover_0.jpg", identityBound = true,
                    profile = ArtistProfile("Preview artist", description = "A preview biography at the bottom of this artist page.", subscribers = 2_400_000, monthlyAudience = "8M monthly audience"), songs = ContentState(songs))
            }
            screenshot("artist-light")
            val stat = device.findObject(By.text("YouTube subscribers"))
            val popular = device.findObject(By.text("Popular songs"))
            assertNotNull(stat); assertNotNull(popular)
            assertTrue(stat.visibleBounds.top < popular.visibleBounds.top)
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 24)
            screenshot("artist-four-songs")
            assertTrue(device.hasObject(By.text(songs[3].title)))
            assertFalse(device.hasObject(By.text(songs[4].title)))
            repeat(3) { device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 24) }
            assertTrue(device.wait(Until.hasObject(By.text("About")), 3000))
            screenshot("artist-about")
            scenario.onActivity { vm!!.closeArtist() }
            click(By.desc("Library"))
            for (mode in listOf(ThemeMode.DARK, ThemeMode.AMOLED)) {
                scenario.onActivity { vm!!.updateTheme { it.copy(mode = mode) } }
                screenshot("library-${mode.name.lowercase()}")
            }
        } finally {
            scenario?.onActivity { vm?.player?.pause(); vm?.playback?.value?.queueIds?.indices?.reversed()?.forEach { vm?.player?.removeQueue(it) } }
            scenario?.close(); delay(300)
            app.library.update { oldLibrary }; app.features.update { oldFeatures }; audio.delete()
        }
        Unit
    }
}
