package com.raagmusic.app.data.youtube

/** Blocking extractor boundary. The repository owns dispatching and cancellation. */
interface ExtractionEngine {
    fun onAudioStreamRejected(trackId: String) { }
    fun onStreamRejected(trackId: String, url: String?) { onAudioStreamRejected(trackId) }
    fun onPlaybackRetry(trackId: String) { }
    fun setCountry(code: String) { }
    fun artistProfileByName(name: String): ArtistProfile? = null
    fun artistSongs(profile: ArtistProfile): List<Track> = emptyList()
    fun artistDetails(profile: ArtistProfile): ArtistProfile = profile
    fun songArtists(trackId: String): List<String> = emptyList()
    fun songArtistCredits(trackId: String): SongArtistCredits = SongArtistCredits(songArtists(trackId))
    fun artistProfile(trackId: String): ArtistProfile? = null
    fun song(trackId: String): Track = throw UnsupportedOperationException("Song links are unavailable")
    fun collection(url: String, kind: CollectionKind): BrowseCollection = throw UnsupportedOperationException("Collection links are unavailable")
    fun relatedSongs(trackId: String): List<Track> = emptyList()
    fun searchSuggestions(query: String): List<String> = emptyList()
    fun searchSongs(query: String): List<Track>
    fun searchPage(query: String, kind: String, cursor: SearchCursor? = null): SearchPage {
        require(cursor == null)
        return when (kind) {
            "All", "Songs" -> SearchPage(tracks = searchSongs(query))
            "Artists" -> SearchPage(artists = listOfNotNull(artistProfileByName(query)))
            "Albums" -> SearchPage(collections = searchCollections(query, CollectionKind.ALBUM))
            "Playlists" -> SearchPage(collections = searchCollections(query, CollectionKind.PLAYLIST))
            else -> SearchPage()
        }
    }
    fun videoStream(trackId: String, maxHeight: Int): ResolvedAudioStream = throw YoutubeExtractionException("No supported video stream is available")
    fun audioStream(trackId: String, maxBitrate: Int): ResolvedAudioStream = ResolvedAudioStream(audioStreamUrl(trackId, maxBitrate))
    fun audioStreamUrl(trackId: String, maxBitrate: Int): String = audioStreamUrl(trackId)
    fun audioStreamUrl(trackId: String): String
    fun searchCollections(query: String, kind: CollectionKind): List<BrowseCollection>
    fun collectionPage(url: String, cursor: CollectionCursor? = null): CollectionPage {
        require(cursor == null)
        return CollectionPage(collectionTracks(url))
    }
    fun collectionTracks(url: String): List<Track>
}

object YoutubeTrackIds {
    private val pattern = Regex("[A-Za-z0-9_-]{11}")
    fun requireValid(id: String): String {
        require(pattern.matches(id)) { "Expected an 11-character YouTube track ID." }
        return id
    }
}

/** Opaque provider cursor, kept in memory only. */
data class CollectionCursor(val page: org.schabi.newpipe.extractor.Page,
    internal val extractor: org.schabi.newpipe.extractor.playlist.PlaylistExtractor? = null) {
    val fingerprint: String get() = listOf(page.url, page.id, page.ids, page.body?.contentHashCode()).toString()
}
data class CollectionPage(val tracks: List<Track>, val next: CollectionCursor? = null)

data class ResolvedAudioStream(val url: String, val bitrate: Int? = null, val formatIdentity: String? = null,
    val expiresAtEpochMillis: Long? = null, val contentLength: Long? = null, val mimeType: String? = null,
    val resolverClient: String? = null, val hasVideo: Boolean = false, val videoHeight: Int? = null)

data class SongArtistCredits(val artists: List<String>, val relatedArtists: List<String> = emptyList(),
    val linkedArtists: List<ArtistProfile> = emptyList())

/** Search continuation remains scoped to its extractor, query and region in memory. */
data class SearchCursor(val page: org.schabi.newpipe.extractor.Page,
    val extractor: org.schabi.newpipe.extractor.search.SearchExtractor) {
    val fingerprint: String get() = listOf(page.url, page.id, page.ids, page.body?.contentHashCode()).toString()
}
data class SearchPage(val tracks: List<Track> = emptyList(), val artists: List<ArtistProfile> = emptyList(),
    val collections: List<BrowseCollection> = emptyList(), val next: SearchCursor? = null,
    val videos: List<Track> = emptyList(), val order: List<String> = emptyList())
