package com.raagmusic.app

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.playback.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
@RunWith(AndroidJUnit4::class)
class CacheFirstPlaybackTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RaagApplication
    private val id = "cacheTest01"
    @Test fun boundedStableWarmupExtendsCachedPrefixWithoutResolvingAgain() = runBlocking {
        val bytes = ByteArray(192 * 1024) { (it % 251).toByte() }
        val file = File(app.cacheDir, "bounded-prefix-fixture").apply { writeBytes(bytes) }
        val stream = ResolvedAudioStream(file.toUri().toString(), 128_000, "bounded-prefix", contentLength = bytes.size.toLong())
        val key = stream.cacheKey(id, "unused")
        val calls = AtomicInteger()
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine(calls, stream)), mediaCache = app.media)
        val reference = YoutubeStreamResolver.mediaUri(id)
        try {
            resolver.setPreloading(reference.toString())
            val resolved = resolver.warm(reference.toString())!!
            app.media.prefetch(DataSpec(reference), true, resolver, maxBytes = 64 * 1024L)
            assertEquals(64 * 1024L, app.media.streamingCache.getCachedBytes(key, 0, Long.MAX_VALUE))
            // This fixture's provider returns a local file; real native responses are HTTPS.
            // Extend using its resolved URI while retaining the same encoding/cache identity.
            app.media.prefetch(resolved, false, resolver, maxBytes = 128 * 1024L)
            assertEquals(128 * 1024L, app.media.streamingCache.getCachedBytes(key, 0, Long.MAX_VALUE))
            assertEquals("Both opens consume the same prepared URL/encoding", 1, calls.get())
            val offlineCalls = AtomicInteger()
            val offline = YoutubeStreamResolver(YoutubeMusicRepository(engine(offlineCalls)), mediaCache = app.media)
            app.media.prefetch(DataSpec(reference), true, offline, maxBytes = 64 * 1024L)
            assertEquals(0, offlineCalls.get())
        } finally { file.delete(); app.media.streamingCache.removeResource(key) }
    }
    @Test fun knownLengthUsesFiniteRangeAndPreservesSeekAndHoleBounds() {
        val stream = ResolvedAudioStream("https://r1.googlevideo.com/videoplayback", 128_000, "finite", contentLength = 500_000)
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine(AtomicInteger(), stream)))
        val reference = YoutubeStreamResolver.mediaUri(id)
        assertEquals(500_000L, resolver.resolveDataSpec(DataSpec(reference)).length)
        assertEquals(400_000L, resolver.resolveDataSpec(DataSpec.Builder().setUri(reference).setPosition(100_000).build()).length)
        assertEquals(32_768L, resolver.resolveDataSpec(DataSpec.Builder().setUri(reference).setPosition(100_000).setLength(32_768).build()).length)
    }
    private fun engine(calls: AtomicInteger, stream: ResolvedAudioStream? = null) = object : ExtractionEngine {
        override fun audioStream(trackId: String, maxBitrate: Int): ResolvedAudioStream { calls.incrementAndGet(); return stream ?: error("Provider must not run for cached audio") }
        override fun audioStreamUrl(trackId: String) = error("Unexpected")
        override fun searchSongs(query: String) = emptyList<Track>()
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<Track>()
    }
    @Test fun newResolverReadsCompleteCachedAudioToEofWithoutProvider() = runBlocking {
        val bytes = ByteArray(48 * 1024) { (it % 251).toByte() }
        val file = File(app.cacheDir, "restart-audio-fixture").apply { writeBytes(bytes) }
        val stream = ResolvedAudioStream("https://r1.googlevideo.com/expired", 128_000, "test-format", 1,
            contentLength = bytes.size.toLong())
        val key = stream.cacheKey(id, "unused")
        val calls = AtomicInteger()
        try {
            app.media.rememberFormat(id, 0, key, stream)
            app.media.prefetch(DataSpec.Builder().setUri(file.toUri()).setKey(key).setLength(bytes.size.toLong()).build(), true)
            file.delete()
            // No in-memory lease survives: the provider deliberately throws and the URL is expired.
            val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine(calls)), mediaCache = app.media)
            val source = ResolvingDataSource.Factory(app.media.playbackFactory(resolver), resolver).createDataSource()
            try {
                assertEquals(bytes.size.toLong(), source.open(DataSpec(YoutubeStreamResolver.mediaUri(id))))
                val actual = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) { val read = source.read(buffer, 0, buffer.size); if (read < 0) break; actual.write(buffer, 0, read) }
                assertArrayEquals(bytes, actual.toByteArray())
                assertEquals(0, calls.get())
            } finally { source.close() }
        } finally { file.delete(); app.media.streamingCache.removeResource(key) }
    }
    @Test fun partialIntroStartsOfflineAndFormatChangesNeverSpliceBytes() = runBlocking {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val file = File(app.cacheDir, "partial-audio-fixture").apply { writeBytes(bytes) }
        val stream = ResolvedAudioStream("https://r1.googlevideo.com/expired", 128_000, "old-format", 1, contentLength = bytes.size.toLong())
        val key = stream.cacheKey(id, "unused")
        val calls = AtomicInteger()
        try {
            app.media.rememberFormat(id, 0, key, stream)
            app.media.prefetch(DataSpec.Builder().setUri(file.toUri()).setKey(key).build(), true)
            file.delete()
            val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine(calls, stream.copy(formatIdentity = "new-format"))), mediaCache = app.media)
            val reference = YoutubeStreamResolver.mediaUri(id)
            val source = ResolvingDataSource.Factory(app.media.playbackFactory(resolver), resolver).createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(reference).setLength(32 * 1024).build())
                val result = ByteArray(32 * 1024); var position = 0
                while (position < result.size) { val n = source.read(result, position, result.size - position); assertTrue(n > 0); position += n }
                assertArrayEquals(bytes.copyOf(result.size), result); assertEquals(0, calls.get())
            } finally { source.close() }
            // Seek into a hole using the original media-item key, as ProgressiveMediaSource does.
            val request = DataSpec.Builder().setUri(reference).setKey(id).setPosition(96 * 1024).build()
            assertThrows(CachedAudioFormatChangedException::class.java) { resolver.resolveDataSpec(request) }
            assertNull(app.media.cachedAudio(DataSpec(YoutubeStreamResolver.mediaUri(id)), id, 0))
        } finally { file.delete(); app.media.streamingCache.removeResource(key) }
    }
    @Test fun rejectedCachedFormatIsForgottenBeforeTryingAnotherEncoding() = runBlocking {
        val file = File(app.cacheDir, "rejected-format-fixture").apply { writeBytes(ByteArray(64 * 1024)) }
        val oldStream = ResolvedAudioStream("https://r1.googlevideo.com/old?c=VISIONOS&itag=251", 128_000,
            "rejected-old-format", contentLength = 128 * 1024L)
        val oldKey = oldStream.cacheKey(id, "unused")
        val fresh = oldStream.copy(url = "https://r1.googlevideo.com/fresh?c=VISIONOS&itag=140", formatIdentity = "fresh-format")
        val freshKey = fresh.cacheKey(id, "unused")
        val calls = AtomicInteger()
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine(calls, fresh)), mediaCache = app.media)
        val reference = YoutubeStreamResolver.mediaUri(id)
        try {
            app.media.rememberFormat(id, 0, oldKey, oldStream)
            app.media.prefetch(DataSpec.Builder().setUri(file.toUri()).setKey(oldKey).setLength(64 * 1024L).build(), true)
            assertEquals(oldKey, resolver.resolveDataSpec(DataSpec(reference)).key)
            resolver.invalidate(reference.toString(), rejected = true)
            val recovered = resolver.resolveDataSpec(DataSpec(YoutubeStreamResolver.mediaUri(id)))
            assertEquals("A rejected disk hint cannot consume the next retry on a format mismatch", freshKey, recovered.key)
            assertEquals(1, calls.get())
        } finally {
            file.delete(); app.media.streamingCache.removeResource(oldKey); app.media.streamingCache.removeResource(freshKey)
        }
    }

}
