package com.raagmusic.app.data.youtube

import com.raagmusic.app.data.retryApiOnce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

/**
 * Native, cold search flows and fresh stream extraction. No server, API key or URL cache.
 * Serialization protects NewPipe's process-wide client/player-JavaScript state.
 */
class YoutubeMusicRepository(
    engine: ExtractionEngine? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialCountry: String = "US",
    metadataDirectory: java.io.File? = null,
    audioSessionDirectory: java.io.File? = null,
) {
    private val engine: ExtractionEngine = engine ?: NewPipeExtractionEngine(sessionDirectory = audioSessionDirectory)
    private val extractionMutex = Mutex()
    // Song loading has its own lane; slow discovery must not block a user pressing Next.
    // Both lanes serialize their own work; engine extractors use per-request regional state.
    private val audioMutex = Mutex()
    private val searchMutex = Mutex()
    private val suggestionMutex = Mutex()
    private val secondaryDiscoveryMutex = Mutex()
    private val portraitPermits = Semaphore(3)
    private val persistentArtists = ArtistMetadataStore(metadataDirectory, loadImmediately = false)
    private val discoveryDisk = com.raagmusic.app.data.DiskMetadataCache(metadataDirectory?.resolve("discovery"))
    @Volatile private var useDiskMetadata = true
    fun cachedArtistProfile(name: String): ArtistProfile? = persistentArtists.profile(name)
    fun cachedSongArtists(id: String): List<String>? = persistentArtists.artists(id)
    private val collectionMetadata = MetadataCache<Pair<String, CollectionKind>, List<BrowseCollection>>()
    private val artistMetadata = MetadataCache<String, ArtistProfile>()
    private val relatedMetadata = MetadataCache<String, List<Track>>()
    fun cachedRelatedSongs(id: String): List<Track> = relatedMetadata.get(id).orEmpty()
    private val creditMetadata = MetadataCache<String, SongArtistCredits>()
    private val artistDetailMetadata = MetadataCache<String, ArtistProfile>()
    private val namedArtistMetadata = MetadataCache<String, ArtistProfile>(capacity = 64)
    private val searchMetadata = MetadataCache<Pair<String, String>, SearchPage>(capacity = 64)
    private val suggestionMetadata = MetadataCache<String, List<String>>()
    @Volatile private var countryCode = com.raagmusic.app.domain.validCountryCode(initialCountry) ?: "US"
    val currentCountry: String get() = countryCode
    init { this.engine.setCountry(countryCode) }

    suspend fun setCountry(code: String) = withContext(ioDispatcher) {
        persistentArtists.load()
        val valid = com.raagmusic.app.domain.validCountryCode(code) ?: throw IllegalArgumentException("Invalid country")
        extractionMutex.withLock {
            engine.setCountry(valid)
            if (countryCode != valid) { countryCode = valid; clearMetadataCache() }
        }
    }

    suspend fun getArtistProfileByName(name: String): ArtistProfile? {
        val cleaned = name.trim().take(120)
        if (cleaned.isBlank()) return null
        cachedArtistProfile(cleaned)?.let { return it }
        return portraitPermits.withPermit { extract(mutex = null) {
            persistentArtists.load()
            persistentArtists.profile(cleaned)?.let { return@extract it }
            val key = cleaned.lowercase(Locale.ROOT)
            namedArtistMetadata.get(key) ?: engine.artistProfileByName(cleaned)?.also { namedArtistMetadata.put(key, it); persistentArtists.put(it) }
        } }
    }
    suspend fun getArtistDetails(name: String): ArtistProfile? {
        val profile = getArtistProfileByName(name) ?: return null
        return getArtistDetails(profile)
    }
    suspend fun getArtistDetails(profile: ArtistProfile): ArtistProfile = extract {
        artistDetailMetadata.get(profile.channelUrl) ?: engine.artistDetails(profile)
            .also { artistDetailMetadata.put(profile.channelUrl, it) }
    }
    private suspend fun getArtistCredits(id: String): SongArtistCredits {
        YoutubeTrackIds.requireValid(id)
        return extract { persistentArtists.load(); creditMetadata.get(id) ?: engine.songArtistCredits(id).also { creditMetadata.put(id, it); persistentArtists.put(id, it.artists) } }
    }
    suspend fun getSongArtists(id: String): List<String> {
        YoutubeTrackIds.requireValid(id)
        return cachedSongArtists(id) ?: withContext(ioDispatcher) { persistentArtists.load(); persistentArtists.artists(id) }
            ?: getArtistCredits(id).artists
    }
    suspend fun getRelatedArtistNames(id: String): List<String> = getArtistCredits(id).relatedArtists

    suspend fun getSongArtistProfile(trackId: String, name: String): ArtistProfile? {
        val credits = getArtistCredits(trackId)
        credits.linkedArtists.firstOrNull { ArtistNames.same(it.name, name) }?.let { return getArtistDetails(it) }
        if (credits.artists.isNotEmpty()) return null
        // A song's uploader is a valid fallback only when it matches the tapped credit.
        // Never use a name search for a song-bound artist: names are not identities.
        return getArtistProfile(trackId)?.takeIf { ArtistNames.same(it.name, name) }
    }
    suspend fun getArtistSongs(profile: ArtistProfile): List<Track> = extract { engine.artistSongs(profile) }

    suspend fun getArtistProfile(trackId: String): ArtistProfile? {
        YoutubeTrackIds.requireValid(trackId)
        return extract { artistMetadata.get(trackId) ?: engine.artistProfile(trackId)?.also { artistMetadata.put(trackId, it) } }
    }

    suspend fun getSong(id: String): Track {
        YoutubeTrackIds.requireValid(id)
        return extract { engine.song(id) }
    }
    suspend fun getCollection(url: String, kind: CollectionKind): BrowseCollection = extract { engine.collection(url, kind) }
    suspend fun getRelatedSongs(id: String): List<Track> {
        YoutubeTrackIds.requireValid(id)
        relatedMetadata.get(id)?.let { return it }
        val diskKey = "$countryCode|related|$id"
        if (useDiskMetadata) withContext(ioDispatcher) { discoveryDisk.read(diskKey)?.optJSONArray("tracks")?.let(TrackMetadataJson::decode) }
            ?.takeIf { it.isNotEmpty() }?.let { relatedMetadata.put(id, it); return it }
        return extract { relatedMetadata.get(id) ?: engine.relatedSongs(id).distinctBy { it.id }.also { tracks ->
            if (tracks.isNotEmpty()) {
                relatedMetadata.put(id, tracks)
                discoveryDisk.write(diskKey, org.json.JSONObject().put("tracks", TrackMetadataJson.encode(tracks)), 6 * 60 * 60_000L)
            }
            persistentArtists.putSongs(tracks)
        } }
    }

    fun searchTracks(query: String, refresh: Boolean = false): Flow<List<Track>> = flow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            emit(emptyList())
        } else {
            emit(searchPage(trimmed, "Songs", refresh = refresh).tracks)
        }
    }.flowOn(ioDispatcher)

    private fun searchKind(kind: String) = kind
    fun cachedSearchPage(query: String, kind: String): SearchPage? =
        searchMetadata.get("$countryCode|${query.trim().take(120).lowercase(Locale.ROOT)}" to searchKind(kind))

    suspend fun searchPage(query: String, kind: String, cursor: SearchCursor? = null, foreground: Boolean = false, refresh: Boolean = false): SearchPage =
        searchPageInLane(query, kind, cursor, refresh, if (foreground) searchMutex else extractionMutex)

    /** Home uses this second lane only after its first page has initialized the Music client. */
    internal suspend fun searchDiscoveryPage(query: String, refresh: Boolean = false): SearchPage =
        searchPageInLane(query, "Songs", null, refresh, secondaryDiscoveryMutex)

    private suspend fun searchPageInLane(query: String, kind: String, cursor: SearchCursor?, refresh: Boolean, lane: Mutex): SearchPage {
        val trimmed = query.trim().take(120)
        if (trimmed.isEmpty()) return SearchPage()
        val key = "$countryCode|${trimmed.lowercase(Locale.ROOT)}" to searchKind(kind)
        if (cursor == null && !refresh) searchMetadata.get(key)?.let { return it }
        return extract(lane) {
            if (cursor == null && !refresh) searchMetadata.get(key)?.let { return@extract it }
            engine.searchPage(trimmed, searchKind(kind), cursor).let { page -> page.copy(
                tracks = page.tracks.distinctBy { it.id }, videos = page.videos.distinctBy { it.id }, artists = page.artists.distinctBy { it.channelUrl },
                collections = page.collections.distinctBy { "${it.kind}:${it.id}" }) }.also { page ->
                if (cursor == null && (page.tracks.isNotEmpty() || page.artists.isNotEmpty() || page.collections.isNotEmpty())) searchMetadata.put(key, page)
            }
        }
    }

    suspend fun searchSuggestions(query: String): List<String> {
        val trimmed = query.trim().take(120)
        if (trimmed.length < 2) return emptyList()
        val key = "$countryCode|${trimmed.lowercase(Locale.ROOT)}"
        suggestionMetadata.get(key)?.let { return it }
        val diskKey = "suggestions|$key"
        if (useDiskMetadata) withContext(ioDispatcher) {
            discoveryDisk.read(diskKey)?.optJSONArray("values")?.let { values ->
                (0 until minOf(values.length(), 6)).map { values.optString(it).trim().take(120) }.filter { it.isNotBlank() }
            }
        }?.let { suggestionMetadata.put(key, it); return it }
        return extract(suggestionMutex) {
            suggestionMetadata.get(key) ?: engine.searchSuggestions(trimmed)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }.take(6)
                .also {
                    suggestionMetadata.put(key, it)
                    discoveryDisk.write(diskKey, org.json.JSONObject().put("values", org.json.JSONArray(it)),
                        if (it.isEmpty()) 10 * 60_000L else com.raagmusic.app.data.DiskMetadataCache.DAY)
                }
        }
    }

    /** Every invocation performs a new extraction; only the caller holds the returned URL. */
    suspend fun getAudioStreamUrl(trackId: String, maxBitrate: Int = 0): String {
        YoutubeTrackIds.requireValid(trackId)
        return extract(audioMutex) { engine.audioStreamUrl(trackId, maxBitrate) }
    }

    suspend fun getAudioStream(trackId: String, maxBitrate: Int = 0): ResolvedAudioStream {
        YoutubeTrackIds.requireValid(trackId)
        return extract(audioMutex) { com.raagmusic.app.playback.PlaybackTrace.provider(trackId) { engine.audioStream(trackId, maxBitrate) } }
    }
    suspend fun getVideoStream(trackId: String, maxHeight: Int): ResolvedAudioStream = extract(audioMutex) { engine.videoStream(YoutubeTrackIds.requireValid(trackId), maxHeight) }
    fun onAudioStreamRejected(trackId: String, url: String? = null) { engine.onStreamRejected(trackId, url) }
    fun onPlaybackRetry(trackId: String) { engine.onPlaybackRetry(trackId) }

    fun searchCollections(query: String, kind: CollectionKind, refresh: Boolean = false): Flow<List<BrowseCollection>> = flow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) emit(emptyList())
        else {
            val key = "$countryCode|${trimmed.lowercase(Locale.ROOT)}" to kind
            val collections = (if (refresh) null else collectionMetadata.get(key)) ?: extract {
                (if (refresh) null else collectionMetadata.get(key)) ?: engine.searchCollections(trimmed, kind).distinctBy { it.id }
                    .also { if (it.isNotEmpty()) collectionMetadata.put(key, it) }
            }
            emit(collections)
        }
    }.flowOn(ioDispatcher)

    suspend fun getCollectionTracks(collection: BrowseCollection): List<Track> =
        extract { engine.collectionTracks(collection.url) }.distinctBy { it.id }

    suspend fun getCollectionPage(collection: BrowseCollection, cursor: CollectionCursor? = null): CollectionPage =
        extract { engine.collectionPage(collection.url, cursor) }.let { page -> page.copy(tracks = page.tracks.distinctBy { it.id }
            .map { if (collection.kind == CollectionKind.ALBUM && it.album.isBlank()) it.copy(album = collection.title) else it }) }

    fun clearMetadataCache() { useDiskMetadata = false; searchMetadata.clear(); collectionMetadata.clear(); suggestionMetadata.clear(); artistMetadata.clear(); namedArtistMetadata.clear(); artistDetailMetadata.clear(); creditMetadata.clear(); relatedMetadata.clear() }

    private suspend fun <T> extract(mutex: Mutex? = extractionMutex, block: () -> T): T = withContext(ioDispatcher) {
        try {
            retryApiOnce(shouldRetry = { error ->
                generateSequence<Throwable>(error) { it.cause }.take(8).none {
                    it is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException ||
                        it is org.schabi.newpipe.extractor.exceptions.ReCaptchaException ||
                        it is org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException ||
                        it is IllegalArgumentException || it is AudioUnavailableException
                }
            }) { if (mutex == null) runInterruptible { block() } else mutex.withLock { runInterruptible { block() } } }
        } catch (e: CancellationException) { throw e }
        catch (e: java.net.UnknownHostException) { throw YoutubeExtractionException("You're offline. Connect to the internet and try again.", e) }
        catch (e: java.net.SocketTimeoutException) { throw YoutubeExtractionException("The connection is taking too long. Please try again.", e) }
        catch (e: YoutubeExtractionException) { throw e }
        catch (e: IOException) { throw YoutubeExtractionException("Couldn't load music. Check your connection and try again.", e) }
        catch (e: Exception) { throw YoutubeExtractionException("YouTube couldn't provide this content. Try again later.", e) }
    }
}

open class YoutubeExtractionException(message: String, cause: Throwable? = null) : IOException(message, cause)

class AudioUnavailableException : YoutubeExtractionException("No supported audio stream is available for this song.")
