package com.raagmusic.app

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@UnstableApi
@RunWith(AndroidJUnit4::class)
class StreamingCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val media get() = (context.applicationContext as RaagApplication).media

    @Test fun prefetchedBytesPlayAfterTheOriginalSourceDisappears() = runBlocking {
        for (metered in listOf(true, false)) {
            val key = "test-intro-${UUID.randomUUID()}"
            val source = File(context.cacheDir, key)
            val bytes = ByteArray(320 * 1024) { (it % 251).toByte() }
            source.writeBytes(bytes)
            val length = if (metered) 64 * 1024 else 256 * 1024
            val spec = DataSpec.Builder().setUri(source.toUri()).setKey(key).build()
            try {
                media.prefetch(spec, metered)
                assertTrue(media.streamingCache.isCached(key, 0, length.toLong()))
                assertFalse(media.streamingCache.isCached(key, length.toLong(), 1))
                assertFalse(media.cache.keys.contains(key)) // Never writes into saved downloads.
                assertTrue(source.delete())
                val cached = media.playbackFactory.createDataSource()
                try {
                    cached.open(spec.buildUpon().setLength(length.toLong()).build())
                    val actual = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = cached.read(actual, read, length - read)
                        assertTrue("Cached intro ended early", count > 0)
                        read += count
                    }
                    assertArrayEquals(bytes.copyOf(length), actual)
                } finally { cached.close() }
            } finally { source.delete(); media.streamingCache.removeResource(key) }
        }
    }

    @Test fun cacheMetadataExcludesSignedRedirectsAndRetainsLengths() {
        val key = "test-metadata-${UUID.randomUUID()}"
        try {
            val mutations = ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 12345)
            ContentMetadataMutations.setRedirectedUri(mutations, "https://cdn.example/audio?sig=private".toUri())
            media.streamingCache.applyContentMetadataMutations(key, mutations)
            val metadata = media.streamingCache.getContentMetadata(key)
            assertNull(ContentMetadata.getRedirectedUri(metadata))
            assertEquals(12345, ContentMetadata.getContentLength(metadata))
        } finally { media.streamingCache.removeResource(key) }
    }
}
