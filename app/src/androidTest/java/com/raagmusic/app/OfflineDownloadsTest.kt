package com.raagmusic.app

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class OfflineDownloadsTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
    private val id = "offlineTest"

    @Test fun youtubeDownloadSurvivesNewResolverAndReadsAndSeeksWithoutNetwork() = runBlocking {
        val bytes = ByteArray(96 * 1024) { (it % 251).toByte() }
        val file = File(app.cacheDir, "offline-download-fixture").apply { writeBytes(bytes) }
        val calls = AtomicInteger()
        val stream = ResolvedAudioStream(file.toUri().toString(), 128_000, "offline-format", contentLength = bytes.size.toLong())
        val factory = CacheDataSource.Factory().setCache(app.media.cache).setUpstreamDataSourceFactory(
            ResolvingDataSource.Factory(DefaultDataSource.Factory(app), OfflineAudioResolver(app.media.cache) { calls.incrementAndGet(); stream }))
        // This is the progressive download path used by DownloadManager for the durable reference.
        val request = DownloadRequest.Builder(id, YoutubeStreamResolver.mediaUri(id)).setCustomCacheKey(id).setMimeType("audio/webm").build()
        val downloader = ProgressiveDownloader(request.toMediaItem(), factory)
        try {
            downloader.download(null)
            assertEquals(1, calls.get())
            assertTrue(app.media.cache.isCached(id, 0, bytes.size.toLong()))
            assertEquals("youtube", request.uri.scheme)
            assertEquals(id, request.customCacheKey)
            assertNull(app.media.cache.getContentMetadata(id).get(ContentMetadata.KEY_REDIRECTED_URI, null as String?))
            file.delete()
            val providerCalls = AtomicInteger()
            val repository = YoutubeMusicRepository(object : ExtractionEngine {
                override fun audioStream(trackId: String, maxBitrate: Int): ResolvedAudioStream { providerCalls.incrementAndGet(); error("Network unavailable") }
                override fun audioStreamUrl(trackId: String) = error("Network unavailable")
                override fun searchSongs(query: String) = emptyList<Track>()
                override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
                override fun collectionTracks(url: String) = emptyList<Track>()
            })
            val resolver = YoutubeStreamResolver(repository, mediaCache = app.media)
            val reference = YoutubeStreamResolver.mediaUri(id)
            assertNotNull(resolver.warm(reference.toString()))
            val playback = ResolvingDataSource.Factory(app.media.playbackFactory(resolver), resolver)
            listOf(0, 40 * 1024).forEach { position ->
                val source = playback.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri(reference).setKey(id).setPosition(position.toLong()).build())
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) { val count = source.read(buffer, 0, buffer.size); if (count < 0) break; output.write(buffer, 0, count) }
                    assertArrayEquals(bytes.copyOfRange(position, bytes.size), output.toByteArray())
                } finally { source.close() }
            }
            assertEquals(0, providerCalls.get())
        } finally { downloader.cancel(); file.delete(); app.media.cache.removeResource(id) }
    }

    @Test fun liveYoutubeSongDownloadsThroughTheServiceAndPlaysOffline() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProviders") == "true")
        val originalLibrary = app.library.state.first()
        val originalFeatures = app.features.state.first()
        val song = app.youtube.getSong("5NV6Rdv1a3I").toLibraryTrack()
        var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
        var vm: com.raagmusic.app.ui.MusicViewModel? = null
        try {
            app.media.downloads.downloadIndex.getDownload(song.id)?.let { org.junit.Assume.assumeTrue("Keep existing downloads", false) }
            app.features.update { it.copy(profile = it.profile.copy(namePromptSeen = true)) }
            scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                vm = androidx.lifecycle.ViewModelProvider(it)[com.raagmusic.app.ui.MusicViewModel::class.java]
                vm!!.download(song)
            }
            kotlinx.coroutines.withTimeout(120_000) {
                while (true) {
                    val download = vm!!.downloads.value[song.id]
                    if (download?.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED) error("Live download failed")
                    if (download?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) break
                    kotlinx.coroutines.delay(200)
                }
            }
            val spec = app.media.downloadedAudio(DataSpec(YoutubeStreamResolver.mediaUri(song.uri)), song.id)
            assertNotNull(spec)
            val source = CacheDataSource.Factory().setCache(app.media.cache)
                .setCacheWriteDataSinkFactory(null).createDataSource()
            var total = 0L
            try {
                source.open(spec!!)
                val buffer = ByteArray(32 * 1024)
                while (true) { val read = source.read(buffer, 0, buffer.size); if (read < 0) break; total += read }
            } finally { source.close() }
            assertEquals(spec!!.length, total)
            android.util.Log.i("GeyamDownloads", "Live service download and offline read: $total bytes")
        } finally {
            if (vm?.downloads?.value?.containsKey(song.id) == true) {
                scenario?.onActivity { vm!!.removeDownload(song.id) }
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    while (app.media.downloads.downloadIndex.getDownload(song.id) != null) kotlinx.coroutines.delay(100)
                }
            }
            scenario?.close()
            app.library.update { originalLibrary }
            app.features.update { originalFeatures }
        }
        Unit
    }

    @Test fun resumedDownloadRejectsAChangedEncodingBeforeAppendingBytes() {
        val file = File(app.cacheDir, "partial-offline-fixture").apply { writeBytes(ByteArray(8192)) }
        val key = "partialTest"
        try {
            val reference = YoutubeStreamResolver.mediaUri(key)
            val spec = DataSpec.Builder().setUri(reference).setKey(key).build()
            val old = ResolvedAudioStream(file.toUri().toString(), formatIdentity = "old", contentLength = 8192)
            OfflineAudioResolver(app.media.cache) { old }.resolveDataSpec(spec)
            CacheWriter(CacheDataSource.Factory().setCache(app.media.cache).setUpstreamDataSourceFactory(DefaultDataSource.Factory(app)).createDataSource(),
                DataSpec.Builder().setUri(file.toUri()).setKey(key).setLength(4096).build(), null, null).cache()
            val next = OfflineAudioResolver(app.media.cache) { old.copy(formatIdentity = "new") }
            assertThrows(java.io.IOException::class.java) { next.resolveDataSpec(spec) }
            assertEquals(4096, app.media.cache.getCachedBytes(key, 0, 8192))
        } finally { file.delete(); app.media.cache.removeResource(key) }
    }

}
