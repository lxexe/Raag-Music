package com.raagmusic.app.data

import com.raagmusic.app.domain.*
import com.raagmusic.app.data.youtube.MetadataCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface LyricsProvider { suspend fun find(track: Track): Lyrics? }

/** A provider failure never prevents trying the next provider. Only definitive misses are cached. */
class LyricsRepository(private val providers: List<LyricsProvider> = listOf(LrcLyricsProvider(), OvhLyricsProvider()), directory: java.io.File? = null) {
    private val cache = MetadataCache<String, Lyrics>(capacity = 64)
    private val disk = DiskMetadataCache(directory, byteBudget = 4 * 1024 * 1024)
    private val mutex = Mutex()
    suspend fun get(track: Track, forceRefresh: Boolean = false): Lyrics {
        val key = "${track.title}|${track.artist}|${track.durationSeconds}"
        if (!forceRefresh) {
            // A cached song must not wait behind a slow request for another song.
            cache.get(key)?.let { return it }
            withContext(Dispatchers.IO) { disk.read(key)?.let(::decode) }?.let { cache.put(key, it); return it }
        }
        return mutex.withLock {
        if (!forceRefresh) cache.get(key)?.let { return@withLock it }
        suspend fun remember(value: Lyrics): Lyrics {
            cache.put(key, value)
            withContext(Dispatchers.IO) {
                val lines = JSONArray().apply { value.lines.take(5_000).forEach { put(JSONObject().put("time", it.timeMs).put("text", it.text)) } }
                disk.write(key, JSONObject().put("plain", value.plain).put("lines", lines).put("instrumental", value.instrumental).put("provider", value.provider),
                    if (value.lines.isEmpty() && value.plain.isBlank() && !value.instrumental) 15 * 60_000L else 7 * DiskMetadataCache.DAY)
            }
            return value
        }
        var failure: Exception? = null
        for (provider in providers) {
            try {
                val result = provider.find(track)
                if (result != null && (result.instrumental || result.lines.isNotEmpty() || result.plain.isNotBlank())) {
                    return@withLock remember(result)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
        }
        if (failure != null) throw IOException("Lyrics providers are unavailable. Please retry.", failure)
        remember(Lyrics(provider = ""))
        }
    }
    private fun decode(json: JSONObject): Lyrics? = runCatching {
        val array = json.getJSONArray("lines")
        val lines = (0 until minOf(array.length(), 5_000)).map { index -> array.getJSONObject(index).let {
            LyricLine(it.getLong("time").coerceIn(0, 7_200_000), it.getString("text").take(2_000))
        } }.sortedBy { it.timeMs }
        Lyrics(lines, json.getString("plain").take(128_000), json.getBoolean("instrumental"), json.getString("provider").take(80))
    }.getOrNull()
}

private class LyricsHttp {
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var nextRequestAt = 0L
    suspend fun json(url: HttpUrl): String? = mutex.withLock {
        val wait = nextRequestAt - System.currentTimeMillis()
        if (wait > 1_000) throw IOException("Lyrics provider is busy.")
        if (wait > 0) delay(wait)
        val request = Request.Builder().url(url).header("User-Agent", "RaagMusic/${com.raagmusic.app.BuildConfig.VERSION_NAME}").build()
        val response = await(client.newCall(request))
        withContext(Dispatchers.IO) { response.use {
            nextRequestAt = System.currentTimeMillis() + 300
            if (it.code == 429 || it.code == 503) {
                val header = it.header("Retry-After").orEmpty()
                nextRequestAt = header.toLongOrNull()?.let { seconds -> System.currentTimeMillis() + seconds.coerceIn(1, 86_400) * 1_000 }
                    ?: runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    ?: (System.currentTimeMillis() + 60_000)
                throw IOException("Lyrics provider is busy.")
            }
            if (it.code == 404) return@withContext null
            if (!it.isSuccessful) throw IOException("Couldn't load lyrics.")
            val body = it.body ?: throw IOException("Empty lyrics response")
            val source = body.source()
            val buffer = okio.Buffer()
            while (buffer.size <= 512_000 && source.read(buffer, minOf(8_192L, 512_001L - buffer.size)) != -1L) { }
            val bytes = buffer.readByteArray()
            if (bytes.size > 512_000) throw IOException("Lyrics response is too large")
            bytes.toString(Charsets.UTF_8)
        } }
    }
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
}

private class LrcLyricsProvider : LyricsProvider {
    private val http = LyricsHttp()
    override suspend fun find(track: Track): Lyrics? {
        val artist = com.raagmusic.app.data.youtube.ArtistNames.fallback(track.artist).firstOrNull() ?: track.artist
        val title = lyricsTrackName(track.title, artist)
        fun url(endpoint: String) = "https://lrclib.net/api/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist)
        val direct = http.json(url("get").apply { if (track.durationSeconds in 1..3600) addQueryParameter("duration", track.durationSeconds.toString()) }.build())
        direct?.let { parse(JSONObject(it)) }?.takeIf { it.instrumental || it.lines.isNotEmpty() || it.plain.isNotBlank() }?.let { return it }
        val search = http.json(url("search").build()) ?: return null
        val records = JSONArray(search)
        return (0 until records.length()).map { records.getJSONObject(it) }
            .filter { lyricIdentityMatches(title, artist, track.durationSeconds, it.optString("trackName"), it.optString("artistName"), it.optDouble("duration", 0.0)) }
            .map(::parse).filter { it.instrumental || it.lines.isNotEmpty() || it.plain.isNotBlank() }
            .sortedByDescending { it.lines.isNotEmpty() }.firstOrNull()
    }
    private fun parse(j: JSONObject): Lyrics {
        fun value(name: String) = if (j.isNull(name)) "" else j.optString(name)
        return Lyrics(parseLrc(value("syncedLyrics")), value("plainLyrics"), j.optBoolean("instrumental"), "LRCLIB")
    }
}

private class OvhLyricsProvider : LyricsProvider {
    private val http = LyricsHttp()
    override suspend fun find(track: Track): Lyrics? {
        val artist = com.raagmusic.app.data.youtube.ArtistNames.fallback(track.artist).firstOrNull() ?: track.artist
        if (artist.isBlank()) return null
        val url = "https://api.lyrics.ovh/v1/".toHttpUrl().newBuilder().addPathSegment(artist)
            .addPathSegment(lyricsTrackName(track.title, artist)).build()
        val response = http.json(url) ?: return null
        val lyrics = JSONObject(response).optString("lyrics").trim()
        return lyrics.takeIf { it.isNotBlank() && it != "null" }?.let { Lyrics(plain = it, provider = "lyrics.ovh") }
    }
}
