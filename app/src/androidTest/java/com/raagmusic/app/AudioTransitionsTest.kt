package com.raagmusic.app

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.CrossfadeController
import com.raagmusic.app.playback.PlaybackEqualizer
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioTransitionsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var audio: File
    private lateinit var main: ExoPlayer
    private var incoming: ExoPlayer? = null
    private lateinit var scope: CoroutineScope
    private lateinit var transitions: CrossfadeController
    private var created = 0
    private fun player(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context))).build()
    @Before fun setup() = runBlocking {
        audio = File(context.cacheDir, "transition-test.wav")
        val count = 8000 * 2 * 8
        val bytes = ByteBuffer.allocate(44 + count).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count)
        audio.writeBytes(bytes.array())
        withContext(Dispatchers.Main) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            main = player()
            main.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { android.util.Log.i("RaagTransitionsTest", "Transition id=${item?.mediaId} reason=$reason position=${main.currentPosition} volume=${main.volume}") }
            })
            transitions = CrossfadeController(main, scope, { created++; player().also { incoming = it } })
            main.setMediaItems(listOf("first", "second").map { MediaItem.Builder().setMediaId(it).setUri(audio.toURI().toString()).build() })
        }
    }
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { transitions.release(); main.release(); scope.cancel() }
        audio.delete(); Unit
    }
    @Test fun crossfadeOverlapsThenSkipsAlreadyHeardIntroAndRestoresVolume() = runBlocking {
        withContext(Dispatchers.Main) {
            transitions.configure(ListeningPreferences(transitionMode = TransitionMode.CROSSFADE, crossfadeSeconds = 2))
            main.seekTo(4000); main.prepare(); main.play()
        }
        eventually { withContext(Dispatchers.Main) { main.isPlaying && incoming?.isPlaying == true && main.volume < .9f && (incoming?.volume ?: 0f) > .1f } }
        android.util.Log.i("RaagTransitionsTest", "Overlap observed")
        try {
            eventually { withContext(Dispatchers.Main) { main.currentMediaItemIndex == 1 && main.isPlaying && main.currentPosition >= 1500 && main.volume > .99f } }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { android.util.Log.e("RaagTransitionsTest", "Handoff timeout index=${main.currentMediaItemIndex} pos=${main.currentPosition} playing=${main.isPlaying} volume=${main.volume} secondaryPos=${incoming?.currentPosition} secondaryPlaying=${incoming?.isPlaying} created=$created") }
            throw e
        }
        Assert.assertEquals(1, created)
    }
    @Test fun pauseAndManualSeekCancelOverlapWithoutASecondAudiblePlayer() = runBlocking {
        withContext(Dispatchers.Main) {
            transitions.configure(ListeningPreferences(transitionMode = TransitionMode.CROSSFADE, crossfadeSeconds = 2))
            main.seekTo(5500); main.prepare(); main.play()
        }
        eventually { withContext(Dispatchers.Main) { incoming?.isPlaying == true && main.volume < .9f } }
        withContext(Dispatchers.Main) { main.pause() }
        eventually { withContext(Dispatchers.Main) { main.volume > .99f && incoming?.isPlaying != true } }
        withContext(Dispatchers.Main) { main.seekTo(0); main.play() }
        delay(400)
        withContext(Dispatchers.Main) { Assert.assertTrue(main.volume > .99f); Assert.assertEquals(0, main.currentMediaItemIndex) }
    }
    @Test fun gaplessModeKeepsQueueContinuousWithoutAnOverlapDecoder() = runBlocking {
        withContext(Dispatchers.Main) { main.seekTo(6500); main.prepare(); main.play() }
        eventually { withContext(Dispatchers.Main) { main.currentMediaItemIndex == 1 && main.isPlaying } }
        Assert.assertEquals(0, created)
        withContext(Dispatchers.Main) { Assert.assertTrue(main.currentPosition < 2000); Assert.assertEquals(1f, main.volume, .001f) }
    }
    @Test fun orderedAlbumSuppressesCrossfadeAndManualNextCancelsAnOverlap() = runBlocking {
        withContext(Dispatchers.Main) {
            main.setMediaItems((0..1).map { index -> MediaItem.Builder().setMediaId("album-$index").setUri(audio.toURI().toString())
                .setMediaMetadata(MediaMetadata.Builder().setAlbumTitle("Continuous Album").setArtist("Same artist").build()).build() })
            transitions.configure(ListeningPreferences(transitionMode = TransitionMode.CROSSFADE, crossfadeSeconds = 2))
            main.seekTo(6500); main.prepare(); main.play()
        }
        eventually { withContext(Dispatchers.Main) { main.currentMediaItemIndex == 1 && main.isPlaying } }
        Assert.assertEquals("An ordered album keeps one player", 0, created)
        withContext(Dispatchers.Main) {
            main.setMediaItems(listOf("first", "second").map { MediaItem.Builder().setMediaId(it).setUri(audio.toURI().toString()).build() })
            main.seekTo(5500); main.prepare(); main.play()
        }
        eventually { withContext(Dispatchers.Main) { incoming?.isPlaying == true && main.volume < .9f } }
        withContext(Dispatchers.Main) { main.seekToNextMediaItem() }
        eventually { withContext(Dispatchers.Main) { main.isPlaying && main.currentMediaItemIndex == 1 && main.volume > .99f && incoming?.isPlaying != true } }
    }
    @Test fun equalizerAttachesToSessionAndReportsSupportWithoutCrashing() = runBlocking {
        val results = mutableListOf<Pair<Boolean,String>>()
        val effect = PlaybackEqualizer { available, status -> results += available to status }
        withContext(Dispatchers.Main) { main.prepare(); main.play() }
        eventually { withContext(Dispatchers.Main) { main.isPlaying && main.audioSessionId > 0 } }
        withContext(Dispatchers.Main) {
            effect.apply(EqualizerSettings(true, EqualizerPreset.BASS))
            effect.attach(main.audioSessionId)
            effect.apply(EqualizerSettings(true, EqualizerPreset.CUSTOM, listOf(2,-2,1,0,3)))
            effect.apply(EqualizerSettings(enabled = false)); effect.release()
        }
        Assert.assertTrue(results.isNotEmpty()); Assert.assertTrue(results.all { it.second.isNotBlank() })
    }
    private suspend fun eventually(block: suspend () -> Boolean) {
        withTimeout(12_000) { while (!block()) delay(50) }
    }
}
