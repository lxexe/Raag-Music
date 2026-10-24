package com.raagmusic.app

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.test.uiautomator.*
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.YoutubeStreamResolver
import com.raagmusic.app.ui.MusicViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoPlaybackIntegrationTest {
    private class Engine(var missingAudio: Boolean = false) : ExtractionEngine {
        var audioCalls = 0; var videoCalls = 0; var quality = 0
        override fun audioStream(trackId: String, maxBitrate: Int): ResolvedAudioStream {
            audioCalls++
            if (missingAudio) throw AudioUnavailableException()
            return ResolvedAudioStream("https://audio.example/file", formatIdentity = "audio")
        }
        override fun videoStream(trackId: String, maxHeight: Int): ResolvedAudioStream {
            videoCalls++; quality = maxHeight
            return ResolvedAudioStream("https://video.example/file", formatIdentity = "video", hasVideo = true)
        }
        override fun audioStreamUrl(trackId: String) = error("Unused")
        override fun searchSongs(query: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<com.raagmusic.app.data.youtube.Track>()
    }
    @Test fun audioIsPreferredAndFallbackOnlyRunsForMissingAudio() {
        val engine = Engine()
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine))
        assertEquals("audio.example", resolver.resolveDataSpec(DataSpec(YoutubeStreamResolver.mediaUri("abcdefghijk"))).uri.host)
        assertEquals(0, engine.videoCalls)
        val missing = Engine(true)
        val fallback = YoutubeStreamResolver(YoutubeMusicRepository(missing))
        assertEquals("video.example", fallback.resolveDataSpec(DataSpec(YoutubeStreamResolver.mediaUri("abcdefghijk"))).uri.host)
        assertEquals(1, missing.audioCalls); assertEquals(1, missing.videoCalls)
    }
    @Test fun alwaysVideoSkipsAudioAndUsesTheChosenQuality() {
        val engine = Engine()
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine), videoMode = { VideoPlaybackMode.ALWAYS }, videoQuality = { VideoQuality.SD })
        val reference = YoutubeStreamResolver.mediaUri("abcdefghijk")
        repeat(3) { resolver.resolveDataSpec(DataSpec(reference)) }
        assertEquals(0, engine.audioCalls); assertEquals(1, engine.videoCalls); assertEquals(480, engine.quality)
    }
    @Test fun liveVideoDecoderAndSurfaceFollowTheVisiblePlayer() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
        val oldFeatures = app.features.state.first(); val oldLibrary = app.library.state.first()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val song = app.youtube.getSong("5NV6Rdv1a3I").toLibraryTrack()
        var scenario: ActivityScenario<MainActivity>? = null
        var vm: MusicViewModel? = null
        suspend fun eventually(label: String, predicate: () -> Boolean) {
            try { withTimeout(40_000) { while (!predicate()) delay(100) } }
            catch (e: TimeoutCancellationException) { throw AssertionError("$label: ${vm?.playback?.value}", e) }
        }
        try {
            app.features.update { it.copy(profile = it.profile.copy(namePromptSeen = true), theme = ThemePreferences(mode = ThemeMode.AMOLED, playerStyle = PlayerStyle.APPLE),
                preferences = it.preferences.copy(videoMode = VideoPlaybackMode.ALWAYS, videoQuality = VideoQuality.LOW, analyticsEnabled = false)) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { vm = ViewModelProvider(it)[MusicViewModel::class.java]; vm!!.play(song, listOf(song), ordered = true, recommendationChain = false) }
            eventually("video source") { vm!!.playback.value.hasVideo && vm!!.playback.value.playing }
            assertFalse("Video stays disabled in the mini player", vm!!.playback.value.videoDecoding)
            repeat(3) {
                device.wait(Until.findObject(By.descContains("Open full player")), 5000)!!.click()
                eventually("visible video decoder") { vm!!.playback.value.videoDecoding && vm!!.playback.value.videoWidth > 0 }
                delay(500)
                device.takeScreenshot(java.io.File(app.getExternalFilesDir(null), "video-player-$it.png"))
                if (it == 0) {
                    device.wait(Until.findObject(By.desc("Full screen video")), 5000)!!.click()
                    assertTrue(device.wait(Until.hasObject(By.desc("Exit full screen video")), 5000))
                    eventually("full-screen decoder") { vm!!.playback.value.videoDecoding && vm!!.playback.value.playing }
                    delay(500)
                    device.takeScreenshot(java.io.File(app.getExternalFilesDir(null), "video-fullscreen.png"))
                    device.findObject(By.desc("Exit full screen video")).click()
                    eventually("inline decoder restored") { vm!!.playback.value.videoDecoding && vm!!.playback.value.playing }
                }
                device.wait(Until.findObject(By.desc("Close player")), 5000)!!.click()
                eventually("decoder released after close") { !vm!!.playback.value.videoDecoding }
            }
            assertTrue(vm!!.playback.value.playing)
        } finally {
            scenario?.onActivity { vm?.player?.pause(); vm?.playback?.value?.queueIds?.indices?.reversed()?.forEach { vm?.player?.removeQueue(it) } }
            scenario?.close()
            delay(300)
            app.library.update { oldLibrary }; app.features.update { oldFeatures }
        }
    }
}
