package com.raagmusic.app

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.raagmusic.app.data.youtube.*
import com.raagmusic.app.playback.YoutubeStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class StreamWarmupTest {
    private class Engine : ExtractionEngine {
        var calls = 0
        var limit = -1
        override fun searchSongs(query: String) = emptyList<Track>()
        override fun searchCollections(query: String, kind: CollectionKind) = emptyList<BrowseCollection>()
        override fun collectionTracks(url: String) = emptyList<Track>()
        override fun audioStreamUrl(trackId: String): String = "https://example.com/audio/${++calls}"
        override fun audioStreamUrl(trackId: String, maxBitrate: Int): String { limit = maxBitrate; return audioStreamUrl(trackId) }
    }
    @Test fun exactQueuedPlayConsumesWarmupAndSeeksReuseItsLease() = runBlocking {
        val engine = Engine()
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine))
        val reference = YoutubeStreamResolver.mediaUri("abcdefghijk", "queued")
        resolver.warm(reference.toString())
        val first = resolver.resolveDataSpec(DataSpec(reference))
        val seek = resolver.resolveDataSpec(DataSpec(reference))
        assertEquals(first.uri, seek.uri); assertEquals(1, engine.calls)
        val replay = YoutubeStreamResolver.mediaUri("abcdefghijk", "new-play")
        assertEquals(first.uri, resolver.resolveDataSpec(DataSpec(replay)).uri)
        assertEquals(1, engine.calls)
        resolver.invalidate(replay.toString())
        resolver.resolveDataSpec(DataSpec(replay))
        assertEquals(2, engine.calls)
    }
    @Test fun qualityChangesAndErrorsInvalidatePreparedUrls() = runBlocking {
        val engine = Engine()
        var limit = 0
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine), { limit })
        val reference = YoutubeStreamResolver.mediaUri("abcdefghijk", "quality")
        resolver.warm(reference.toString())
        limit = 64_000
        resolver.resolveDataSpec(DataSpec(reference))
        assertEquals(2, engine.calls); assertEquals(64_000, engine.limit)
        resolver.invalidate()
        resolver.resolveDataSpec(DataSpec(reference))
        assertEquals(3, engine.calls)
    }
    @Test fun longTrackHorizonRefreshesAnInsufficientWarmup() = runBlocking {
        val engine = Engine()
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine))
        val reference = YoutubeStreamResolver.mediaUri("abcdefghijk", "long-track")
        resolver.warm(reference.toString())
        resolver.warm(reference.toString(), requiredValidityMillis = 600_000)
        assertEquals(2, engine.calls)
        resolver.resolveDataSpec(DataSpec(reference))
        assertEquals(2, engine.calls)
    }
    @Test fun crossfadeAndMainUseTheSamePreparedEncodingAndByteKey() = runBlocking {
        val engine = Engine()
        val resolver = YoutubeStreamResolver(YoutubeMusicRepository(engine))
        val reference = YoutubeStreamResolver.mediaUri("abcdefghijk", "crossfade")
        val warm = resolver.warm(reference.toString())!!
        val incoming = resolver.resolveDataSpec(DataSpec(reference))
        resolver.resolveDataSpec(DataSpec(YoutubeStreamResolver.mediaUri("lmnopqrstuv", "outgoing")))
        val main = resolver.resolveDataSpec(DataSpec(reference))
        assertEquals(warm.uri, incoming.uri)
        assertEquals(incoming.uri, main.uri)
        assertEquals(warm.key, main.key)
        assertEquals(2, engine.calls)
    }
}
