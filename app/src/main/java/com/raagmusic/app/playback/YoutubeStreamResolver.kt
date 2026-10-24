package com.raagmusic.app.playback

import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.raagmusic.app.data.youtube.YoutubeMusicRepository
import com.raagmusic.app.data.youtube.YoutubeTrackIds
import kotlinx.coroutines.runBlocking
import java.io.InterruptedIOException

/** The queue stores youtube://track/VIDEO_ID, never a signed URL. */
@UnstableApi
class YoutubeStreamResolver(private val repository: YoutubeMusicRepository, private val bitrateLimit: () -> Int = { 0 },
    private val mediaCache: MediaCache? = null,
    private val onResolved: (String, Int?) -> Unit = { _, _ -> },
    private val videoMode: () -> com.raagmusic.app.domain.VideoPlaybackMode = { com.raagmusic.app.domain.VideoPlaybackMode.FALLBACK },
    private val videoQuality: () -> com.raagmusic.app.domain.VideoQuality = { com.raagmusic.app.domain.VideoQuality.HIGHEST }) : ResolvingDataSource.Resolver {
    private data class FormatPin(val key: String, val limit: Int)
    private val pins = LinkedHashMap<String, FormatPin>()
    private fun pin(reference: String, key: String, limit: Int) = synchronized(pins) {
        pins[reference] = FormatPin(key, limit)
        while (pins.size > 64) pins.remove(pins.keys.first())
    }
    private fun pinned(reference: String) = synchronized(pins) { pins[reference] }
    @Volatile private var preloading: String? = null
    fun setPreloading(reference: String?) { preloading = reference }
    private val videos = java.util.concurrent.ConcurrentHashMap<String, com.raagmusic.app.data.youtube.ResolvedAudioStream>()
    private val resolvedStreams = java.util.concurrent.ConcurrentHashMap<String, com.raagmusic.app.data.youtube.ResolvedAudioStream>()
    private suspend fun video(reference: String, id: String): com.raagmusic.app.data.youtube.ResolvedAudioStream {
        videos[reference]?.takeIf { (it.expiresAtEpochMillis ?: Long.MAX_VALUE) - System.currentTimeMillis() > 60_000 }?.let { return it }
        val stream = repository.getVideoStream(id, videoQuality().maxHeight)
        if (videos.size >= 2) videos.clear()
        videos[reference] = stream
        return stream
    }
    private suspend fun stream(reference: String, id: String, limit: Int, warm: Boolean = false, validity: Long = 0): com.raagmusic.app.data.youtube.ResolvedAudioStream {
        if (videoMode() == com.raagmusic.app.domain.VideoPlaybackMode.ALWAYS) return video(reference, id)
        return try { prepared.resolve(reference, id, limit, warm = warm, requiredValidityMillis = validity) }
        catch (_: com.raagmusic.app.data.youtube.AudioUnavailableException) { video(reference, id) }
    }
    private val prepared = PreparedAudioStreams(repository::getAudioStream)
    suspend fun warm(reference: String, requiredValidityMillis: Long = 0): DataSpec? {
        val uri = reference.toUri()
        if (uri.scheme != SCHEME) return null
        require(uri.host == "track" && uri.pathSegments.size == 1) { "Invalid YouTube media reference." }
        val id = YoutubeTrackIds.requireValid(uri.lastPathSegment.orEmpty())
        if (videoMode() != com.raagmusic.app.domain.VideoPlaybackMode.ALWAYS) mediaCache?.downloadedAudio(DataSpec(uri), id)?.let { return it }
        val limit = bitrateLimit()
        if (videoMode() != com.raagmusic.app.domain.VideoPlaybackMode.ALWAYS) mediaCache?.cachedAudio(DataSpec(uri), id, limit)?.let { return it.first }
        val stream = stream(reference, id, limit, warm = true, validity = requiredValidityMillis)
        return resolved(DataSpec(uri), stream, id)
    }
    fun invalidate(reference: String? = null, rejected: Boolean = false) {
        if (rejected) reference?.toUri()?.lastPathSegment?.let { id ->
            repository.onAudioStreamRejected(id, resolvedStreams[reference]?.url)
            pinned(reference)?.key?.let { mediaCache?.forgetFormat(it) }
            videos.keys.removeAll { it.toUri().lastPathSegment == id }
        }
        if (reference == null) resolvedStreams.clear() else resolvedStreams.remove(reference)
        if (reference == null) videos.clear() else videos.remove(reference)
        prepared.invalidate(reference)
        synchronized(pins) { if (reference == null) pins.clear() else pins.remove(reference) }
    }
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != SCHEME) return dataSpec
        require(dataSpec.uri.host == "track" && dataSpec.uri.pathSegments.size == 1) {
            "Invalid YouTube media reference."
        }
        val id = YoutubeTrackIds.requireValid(dataSpec.uri.lastPathSegment.orEmpty())
        if (videoMode() != com.raagmusic.app.domain.VideoPlaybackMode.ALWAYS) mediaCache?.downloadedAudio(dataSpec, id)?.let { return it }
        val reference = dataSpec.uri.toString()
        val selected = pinned(reference)
        val limit = selected?.limit ?: bitrateLimit()
        if (videoMode() != com.raagmusic.app.domain.VideoPlaybackMode.ALWAYS && !videos.containsKey(reference)) mediaCache?.cachedAudio(dataSpec, id, limit, selected?.key)?.let { (cached, bitrate) ->
            pin(reference, cached.key!!, limit)
            PlaybackTrace.mark(id, "disk_cache_start")
            onResolved(id, bitrate)
            return cached
        }
        // A seek can reopen outside cached ranges before any URL has been resolved. Carry the
        // started play's encoding into that open too, not just into contiguous cache holes.
        return resolveCacheMiss(if (selected == null) dataSpec else dataSpec.buildUpon().setKey(selected.key).build())
    }

    internal fun resolveCacheMiss(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != SCHEME) return dataSpec
        require(dataSpec.uri.host == "track" && dataSpec.uri.pathSegments.size == 1)
        val id = YoutubeTrackIds.requireValid(dataSpec.uri.lastPathSegment.orEmpty())
        val limit = pinned(dataSpec.uri.toString())?.limit ?: bitrateLimit()
        // Media3 invokes this on its LOADER thread, never the UI thread. Its synchronous
        // DataSource contract needs this bridge; all extraction still runs on Dispatchers.IO.
        val started = System.nanoTime()
        PlaybackTrace.mark(id, "resolve_start")
        val stream = try {
            runBlocking { stream(dataSpec.uri.toString(), id, limit, warm = dataSpec.uri.toString() == preloading) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Audio resolution cancelled").apply { initCause(e) }
        }
        PlaybackTrace.mark(id, "resolve_end", (System.nanoTime() - started) / 1_000_000)
        onResolved(id, stream.bitrate)
        val result = resolved(dataSpec, stream, id)
        if (dataSpec.key?.startsWith("youtube-audio/") == true && dataSpec.key != result.key) {
            mediaCache?.forgetFormat(dataSpec.key!!)
            throw CachedAudioFormatChangedException()
        }
        val resolvedLimit = prepared.resolvedLimit(dataSpec.uri.toString()) ?: limit
        if (mediaCache != null && stream.formatIdentity != null) pin(dataSpec.uri.toString(), result.key!!, resolvedLimit)
        mediaCache?.rememberFormat(id, resolvedLimit, result.key!!, stream)
        return result
    }

    private fun resolved(dataSpec: DataSpec, stream: com.raagmusic.app.data.youtube.ResolvedAudioStream, id: String): DataSpec {
        if (resolvedStreams.size >= 64) resolvedStreams.clear()
        resolvedStreams[dataSpec.uri.toString()] = stream
        // A finite Range avoids the CDN's open-ended response pacing. This is the same
        // remaining file, not a short chunk or artificial EOF; seeks/cache holes keep their bounds.
        val remaining = stream.contentLength?.minus(dataSpec.position)?.takeIf { it > 0 }
        val length = if (remaining == null) dataSpec.length else if (dataSpec.length == androidx.media3.common.C.LENGTH_UNSET.toLong())
            remaining else minOf(dataSpec.length, remaining)
        return dataSpec.buildUpon().setUri(stream.url.toUri()).setKey(stream.cacheKey(id, dataSpec.uri.toString())).setLength(length)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(youtubeStreamBody)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + youtubeStreamHeaders(stream.url)).build()
    }

    companion object {
        const val SCHEME = "youtube"
        fun mediaUri(id: String, playToken: String = java.util.UUID.randomUUID().toString()) =
            "$SCHEME://track/${YoutubeTrackIds.requireValid(id)}?play=$playToken".toUri()
    }
}

/** Force a clean media-source retry; never splice two encodings in one cached file. */
internal class CachedAudioFormatChangedException : java.io.IOException("The cached audio encoding has changed")
