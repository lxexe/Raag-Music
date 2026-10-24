package com.raagmusic.app.playback

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.raagmusic.app.data.youtube.ResolvedAudioStream
import com.raagmusic.app.data.youtube.YoutubeTrackIds
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InterruptedIOException

/** Resolve durable download references on the loader thread; signed URLs stay in memory. */
@UnstableApi
internal class OfflineAudioResolver(
    private val cache: Cache,
    private val resolve: suspend (String) -> ResolvedAudioStream,
) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != YoutubeStreamResolver.SCHEME) return dataSpec
        require(dataSpec.uri.host == "track" && dataSpec.uri.pathSegments.size == 1)
        val id = YoutubeTrackIds.requireValid(dataSpec.uri.lastPathSegment.orEmpty())
        val stream = try { runBlocking { resolve(id) } }
        catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Download cancelled").apply { initCause(e) }
        }
        val format = stream.formatIdentity ?: throw IOException("No downloadable audio format available")
        val length = stream.contentLength?.takeIf { it > 0 } ?: throw IOException("Audio length unavailable")
        val identity = "$format/$length"
        val key = dataSpec.key ?: id
        val previous = cache.getContentMetadata(key).get(FORMAT, "").orEmpty()
        // Resuming a different encoding would silently splice incompatible audio bytes.
        if (previous.isNotEmpty() && previous != identity && cache.getCachedSpans(key).isNotEmpty()) {
            throw IOException("Audio format changed. Remove the download and download it again.")
        }
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(FORMAT, identity))
        val remaining = length - dataSpec.position
        if (remaining <= 0) throw IOException("Invalid download position")
        return dataSpec.buildUpon().setUri(stream.url.toUri())
            .setLength(if (dataSpec.length < 0) remaining else minOf(dataSpec.length, remaining))
            .setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(youtubeStreamBody)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + youtubeStreamHeaders(stream.url)).build()
    }

    companion object { private const val FORMAT = "geyam.offline.format" }
}
