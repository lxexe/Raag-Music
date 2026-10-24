package com.raagmusic.app.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers

/** One cache owner per process. Downloaded songs are never silently evicted. */
@UnstableApi
class MediaCache(private val context: Context,
    resolveDownload: suspend (String) -> com.raagmusic.app.data.youtube.ResolvedAudioStream) {
    private val database = StandaloneDatabaseProvider(context)
    private val offlineStorage = SimpleCache(File(context.filesDir, "offline_audio"), NoOpCacheEvictor(), database)
    val cache = object : Cache by offlineStorage {
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
            offlineStorage.applyContentMetadataMutations(key, mutations.remove(ContentMetadata.KEY_REDIRECTED_URI))
        }
    }
    private val streamingStorage = SimpleCache(File(context.cacheDir, "streaming_audio"),
        LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024), database)
    // CacheDataSource otherwise persists a redirected CDN URL in its content metadata.
    // Retain only stable format keys, byte ranges and lengths; signing stays in memory.
    internal val streamingCache = object : Cache by streamingStorage {
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
            streamingStorage.applyContentMetadataMutations(key, mutations.remove(ContentMetadata.KEY_REDIRECTED_URI))
        }
    }
    private val http = DefaultHttpDataSource.Factory().setUserAgent("Raag/1.0").setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
    private fun streamingFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(streamingCache).setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, upstream))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    val playbackFactory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(streamingFactory(http))
        .setCacheWriteDataSinkFactory(null) // Playback cache eviction never touches saved downloads.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    /** Cache hits never enter a provider. Only a hole in the selected encoding resolves a URL. */
    fun playbackFactory(resolver: YoutubeStreamResolver): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(streamingFactory(ResolvingDataSource.Factory(http, resolver::resolveCacheMiss)))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** A complete offline file bypasses URL resolution, including in airplane mode. */
    internal fun downloadedAudio(spec: DataSpec, id: String): DataSpec? {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(id))
        if (length <= 0 || !cache.isCached(id, 0, length)) return null
        val remaining = length - spec.position
        if (remaining <= 0) return null
        return spec.buildUpon().setKey(id)
            .setLength(if (spec.length < 0) remaining else minOf(spec.length, remaining)).build()
    }

    internal fun rememberFormat(id: String, limit: Int, key: String, stream: com.raagmusic.app.data.youtube.ResolvedAudioStream) {
        if (stream.formatIdentity == null || stream.hasVideo) return
        val length = stream.contentLength?.takeIf { it > 0 } ?: return
        val old = streamingCache.getContentMetadata(key)
        if (old.get("geyam.id", "") == id && old.get("geyam.limit", -1L) == limit.toLong()) return
        streamingCache.applyContentMetadataMutations(key, ContentMetadataMutations()
            .set("geyam.id", id).set("geyam.limit", limit.toLong()).set("geyam.length", length)
            .set("geyam.bitrate", (stream.bitrate ?: 0).toLong()))
    }
    internal fun forgetFormat(key: String) {
        streamingCache.applyContentMetadataMutations(key, ContentMetadataMutations().remove("geyam.id"))
    }
    internal fun cachedAudio(spec: DataSpec, id: String, limit: Int, expectedKey: String? = null): Pair<DataSpec, Int?>? {
        for (key in streamingCache.keys.filter { it.startsWith("youtube-audio/$id/") && (expectedKey == null || it == expectedKey) }) {
            val metadata = streamingCache.getContentMetadata(key)
            if (metadata.get("geyam.id", "") != id || metadata.get("geyam.limit", -1L) != limit.toLong()) continue
            val remaining = metadata.get("geyam.length", -1L) - spec.position
            if (remaining <= 0 || !streamingCache.isCached(key, spec.position, minOf(32 * 1024L, remaining))) continue
            val length = if (spec.length == androidx.media3.common.C.LENGTH_UNSET.toLong()) remaining else minOf(spec.length, remaining)
            return spec.buildUpon().setKey(key).setLength(length).build() to metadata.get("geyam.bitrate", 0L).toInt().takeIf { it > 0 }
        }
        return null
    }
    private val prefetchHttp = DefaultHttpDataSource.Factory().setUserAgent("Raag/1.0")
        .setConnectTimeoutMs(5000).setReadTimeoutMs(5000)
    private val prefetchFactory = streamingFactory(prefetchHttp)

    /** Only the next intro, after current playback is buffered. Never download the full queue. */
    suspend fun prefetch(dataSpec: DataSpec, metered: Boolean, resolver: YoutubeStreamResolver? = null,
        maxBytes: Long = if (metered) 64L * 1024 else 256L * 1024) {
        val length = if (dataSpec.length == androidx.media3.common.C.LENGTH_UNSET.toLong()) maxBytes else minOf(maxBytes, dataSpec.length)
        val bounded = dataSpec.buildUpon().setPosition(0).setLength(length).build()
        val resolve = dataSpec.uri.scheme == YoutubeStreamResolver.SCHEME && resolver != null
        val selected = if (resolve) runInterruptible(Dispatchers.IO) { resolver!!.resolveDataSpec(bounded) } else bounded
        val factory = if (resolve) streamingFactory(ResolvingDataSource.Factory(prefetchHttp, resolver!!::resolveCacheMiss)) else prefetchFactory
        val writer = CacheWriter(factory.createDataSource(),
            selected, ByteArray(32 * 1024), null)
        try { runInterruptible(Dispatchers.IO) { writer.cache() } }
        finally { writer.cancel() }
    }
    private val downloadFactory = ResolvingDataSource.Factory(http, OfflineAudioResolver(cache, resolveDownload))
    val downloads = DownloadManager(context, database, cache, downloadFactory, Executors.newFixedThreadPool(2)).apply {
        maxParallelDownloads = 2
    }
}
