package com.raagmusic.app

import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.raagmusic.app.domain.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class PlaybackFeaturesTest {
    @get:Rule val testName = org.junit.rules.TestName()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RaagApplication
    private lateinit var originalLibrary: LibraryState
    private lateinit var originalFeatures: FeatureState
    private var browserFuture: ListenableFuture<MediaBrowser>? = null
    private lateinit var browser: MediaBrowser
    private lateinit var a: Track
    private lateinit var b: Track
    private lateinit var c: Track
    private lateinit var audio: File

    @Before fun setup() = runBlocking {
        fun fixture(id: String) = id.startsWith("feature-test-")
        originalLibrary = app.library.state.first().let { state -> state.copy(
            savedTracks = state.savedTracks.filterNot { fixture(it.id) },
            favorites = state.favorites.filterNot(::fixture).toSet(),
            recentIds = state.recentIds.filterNot(::fixture),
            playlists = state.playlists.filterNot { it.id == "feature-test" }) }
        originalFeatures = app.features.state.first().let { state -> state.copy(
            queue = state.queue.copy(tracks = state.queue.tracks.filterNot { fixture(it.id) }).sanitized(),
            history = state.history.filterNot { fixture(it.trackId) }) }
        audio = File(context.cacheDir, "feature-test.wav")
        val dataBytes = 8000 * 2 * 30
        val bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(dataBytes)
        audio.writeBytes(bytes.array())
        a = Track("feature-test-a", "Feature test A", "Test artist", "", audio.toURI().toString())
        b = a.copy(id = "feature-test-b", title = "Feature test B")
        c = a.copy(id = "feature-test-c", title = "Feature test C")
        app.library.update { originalLibrary.copy(savedTracks = (originalLibrary.savedTracks + listOf(a,b,c)).distinctBy { it.id },
            favorites = setOf(a.id), recentIds = listOf(b.id), playlists = listOf(Playlist("feature-test", "Test playlist", listOf(c.id, a.id)))) }
        app.features.update { FeatureState(queue = QueueSnapshot(listOf(a,b,c), 1, 12_345, true, Player.REPEAT_MODE_ALL, recommendationChain = testName.methodName.startsWith("test01"))) }
        withContext(Dispatchers.Main) {
            browserFuture = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
            browser = await(requireNotNull(browserFuture))
        }
        if (!testName.methodName.startsWith("test01")) withContext(Dispatchers.Main) {
            browser.setMediaItems(listOf(a,b,c).map { it.asMediaItem() }, 1, 12_345)
            browser.shuffleModeEnabled = true; browser.repeatMode = Player.REPEAT_MODE_ALL
        }
        eventually { withContext(Dispatchers.Main) { browser.mediaItemCount == 3 &&
            (!testName.methodName.startsWith("test01") || browser.sessionExtras.getBoolean(RECOMMENDATION_CHAIN)) } }
    }
    @After fun cleanup() = runBlocking {
        // System UI legitimately keeps a MediaBrowser binding after playback. Empty the
        // fixture queue before restoring the user's data rather than forcing service death.
        withContext(Dispatchers.Main) { if (::browser.isInitialized) { browser.pause(); browser.clearMediaItems() } }
        eventually { app.features.state.first().queue.tracks.isEmpty() }
        withContext(Dispatchers.Main) { browserFuture?.let { MediaController.releaseFuture(it) } }
        context.stopService(Intent(context, PlaybackService::class.java))
        delay(200)
        app.library.update { originalLibrary }
        app.features.update { originalFeatures }
        audio.delete()
        Unit
    }
    @Test fun test01RestoredQueuePreservesPositionOrderModesAndStaysPaused() = runBlocking {
        withContext(Dispatchers.Main) {
            Assert.assertEquals(listOf(a.id,b.id,c.id), List(browser.mediaItemCount) { browser.getMediaItemAt(it).mediaId })
            Assert.assertEquals(1, browser.currentMediaItemIndex)
            Assert.assertEquals(12_345L, browser.currentPosition)
            Assert.assertTrue(browser.shuffleModeEnabled)
            Assert.assertEquals(Player.REPEAT_MODE_ALL, browser.repeatMode)
            Assert.assertFalse(browser.isPlaying); Assert.assertFalse(browser.playWhenReady)
            Assert.assertTrue(browser.sessionExtras.getBoolean(RECOMMENDATION_CHAIN))
        }
    }
    @Test fun test02EditsPersistWithCurrentSongPositionIntact() = runBlocking {
        withContext(Dispatchers.Main) {
            browser.moveMediaItem(2, 0)
            browser.shuffleModeEnabled = false; browser.repeatMode = Player.REPEAT_MODE_ONE
        }
        eventually {
            val q = app.features.state.first().queue
            q.tracks.map { it.id } == listOf(c.id,a.id,b.id) && q.index == 2 && q.positionMs == 12_345L && !q.shuffle && q.repeat == Player.REPEAT_MODE_ONE
        }
        withContext(Dispatchers.Main) { browser.removeMediaItem(0) }
        eventually { app.features.state.first().queue.tracks.map { it.id } == listOf(a.id,b.id) }
    }
    @Test fun test03MediaBrowserServesFavoritesPlaylistsRecentAndSearch() = runBlocking {
        withContext(Dispatchers.Main) {
            val root = await(browser.getLibraryRoot(null))
            Assert.assertEquals("root", root.value?.mediaId)
            val children = await(browser.getChildren("root", 0, 20, null)).value.orEmpty()
            Assert.assertEquals(listOf("favorites", "playlists", "recent"), children.map { it.mediaId })
            Assert.assertEquals(listOf(a.id), await(browser.getChildren("favorites", 0, 20, null)).value.orEmpty().map { it.mediaId })
            Assert.assertEquals(listOf(c.id,a.id), await(browser.getChildren("playlist:feature-test", 0, 20, null)).value.orEmpty().map { it.mediaId })
            Assert.assertEquals(listOf(b.id), await(browser.getChildren("recent", 0, 20, null)).value.orEmpty().map { it.mediaId })
            Assert.assertTrue(await(browser.getItem(a.id)).value?.mediaMetadata?.isPlayable == true)
            await(browser.search("Feature test", null))
            Assert.assertEquals(3, await(browser.getSearchResult("Feature test", 0, 20, null)).value?.size)
        }
    }
    @Test fun test05ListeningCountsOnlyPlayingTimeAndRecordsEarlySkips() = runBlocking {
        withContext(Dispatchers.Main) { browser.shuffleModeEnabled = false; browser.seekTo(0, 0); browser.prepare(); browser.play() }
        eventually { withContext(Dispatchers.Main) { browser.isPlaying } }
        delay(2200)
        withContext(Dispatchers.Main) { browser.seekToNextMediaItem() }
        eventually { app.features.state.first().history.any { it.trackId == a.id && it.skips > 0 && it.milliseconds > 0 } }
        withContext(Dispatchers.Main) { browser.pause() }
        delay(600)
        val before = app.features.state.first().history.sumOf { it.milliseconds }
        delay(1500)
        val after = app.features.state.first().history.sumOf { it.milliseconds }
        Assert.assertEquals(before, after)
    }
    @Test fun test04OptingOutClearsSavedQueueAndStopsHistoryRecords() = runBlocking {
        app.features.update { it.copy(preferences = it.preferences.copy(rememberQueue = false, collectHistory = false), history = emptyList()) }
        eventually { app.features.state.first().queue.tracks.isEmpty() }
        withContext(Dispatchers.Main) { browser.prepare(); browser.play() }
        eventually { withContext(Dispatchers.Main) { browser.isPlaying } }
        delay(1200)
        withContext(Dispatchers.Main) { browser.pause() }
        delay(500)
        Assert.assertTrue(app.features.state.first().history.isEmpty())
    }
    @Test fun test06WidgetControlsAndPlaylistShortcuts() = runBlocking {
        fun send(action: String) = context.sendBroadcast(Intent(context, com.raagmusic.app.widget.RaagWidget::class.java).setAction(action)
            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 998))
        withContext(Dispatchers.Main) { browser.shuffleModeEnabled = false }
        // Controller setters optimistically mask state. A second controller (the widget)
        // can connect before async onAddMediaItems and the shuffle command reach the service.
        eventually {
            val saved = app.features.state.first().queue
            !saved.shuffle && saved.index == 1 && saved.tracks.map { it.id } == listOf(a.id, b.id, c.id)
        }
        android.util.Log.i("RaagWidgetTest", "Sending NEXT"); send(com.raagmusic.app.widget.RaagWidget.NEXT)
        eventually { withContext(Dispatchers.Main) { browser.currentMediaItem?.mediaId == c.id } }
        android.util.Log.i("RaagWidgetTest", "Sending PREVIOUS"); send(com.raagmusic.app.widget.RaagWidget.PREVIOUS)
        eventually { withContext(Dispatchers.Main) { browser.currentMediaItem?.mediaId == b.id } }
        android.util.Log.i("RaagWidgetTest", "Sending TOGGLE"); send(com.raagmusic.app.widget.RaagWidget.TOGGLE)
        eventually { withContext(Dispatchers.Main) { browser.isPlaying } }
        android.util.Log.i("RaagWidgetTest", "Sending TOGGLE"); send(com.raagmusic.app.widget.RaagWidget.TOGGLE)
        eventually { withContext(Dispatchers.Main) { !browser.playWhenReady } }
        android.util.Log.i("RaagWidgetTest", "Sending FAVORITES"); send(com.raagmusic.app.widget.RaagWidget.FAVORITES)
        eventually { withContext(Dispatchers.Main) { browser.mediaItemCount == 1 && browser.currentMediaItem?.mediaId == a.id } }
        context.getSharedPreferences("widgets", android.content.Context.MODE_PRIVATE).edit().putString("playlist:998", "feature-test").apply()
        android.util.Log.i("RaagWidgetTest", "Sending PLAYLIST"); send(com.raagmusic.app.widget.RaagWidget.PLAYLIST)
        eventually { withContext(Dispatchers.Main) { browser.mediaItemCount == 2 && browser.currentMediaItem?.mediaId == c.id } }
        context.getSharedPreferences("widgets", android.content.Context.MODE_PRIVATE).edit().remove("playlist:998").apply()
    }
    @Test fun test07ExitCommandHonorsPreferenceAndKeepsQueueForPausedRestore() = runBlocking {
        withContext(Dispatchers.Main) { browser.prepare(); browser.play() }
        eventually { withContext(Dispatchers.Main) { browser.isPlaying } }
        suspend fun exit() = withContext(Dispatchers.Main) {
            await(browser.sendCustomCommand(SessionCommand(PlaybackService.EXIT_APP, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY))
        }
        exit()
        withContext(Dispatchers.Main) { Assert.assertTrue(browser.playWhenReady) }
        app.features.update { it.copy(preferences = it.preferences.copy(stopOnExit = true)) }
        exit()
        eventually { withContext(Dispatchers.Main) { !browser.playWhenReady && browser.playbackState == Player.STATE_IDLE } }
        Assert.assertEquals(3, app.features.state.first().queue.tracks.size)
    }

    @Test fun test08ClosingActivityStopsOnlyWhenExitSettingIsEnabled() = runBlocking {
        withContext(Dispatchers.Main) { browser.prepare(); browser.play() }
        eventually { withContext(Dispatchers.Main) { browser.isPlaying } }
        for (stop in listOf(false, true)) {
            app.features.update { it.copy(preferences = it.preferences.copy(stopOnExit = stop), profile = ListenerProfile("", true)) }
            val scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            try {
                delay(600)
                scenario.onActivity { it.finish() }
                if (stop) eventually { withContext(Dispatchers.Main) { !browser.playWhenReady && browser.playbackState == Player.STATE_IDLE } }
                else withContext(Dispatchers.Main) { Assert.assertTrue(browser.playWhenReady) }
            } finally { scenario.close() }
        }
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(100)
    }
    private suspend fun <T> await(future: ListenableFuture<T>): T = suspendCancellableCoroutine { c ->
        c.invokeOnCancellation { future.cancel(true) }
        future.addListener({ try { if (c.isActive) c.resume(future.get()) } catch (e: Exception) { if (c.isActive) c.resumeWithException(e) } }, MoreExecutors.directExecutor())
    }
}
