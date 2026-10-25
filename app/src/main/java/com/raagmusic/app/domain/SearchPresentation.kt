package com.raagmusic.app.domain

/** Shared entity ranking used to choose relevant category previews. */
sealed interface SearchEntry {
    val key: String
    data class Song(val track: Track) : SearchEntry { override val key = "track:${track.id}" }
    data class Suggestion(val query: String) : SearchEntry { override val key = "suggestion:$query" }
    data class Video(val track: Track) : SearchEntry { override val key = "video:${track.id}" }
    data class Artist(val profile: com.raagmusic.app.data.youtube.ArtistProfile) : SearchEntry {
        override val key = "artist:${profile.channelUrl.substringAfterLast('/')}"
    }
    data class CollectionResult(val collection: com.raagmusic.app.data.youtube.BrowseCollection) : SearchEntry {
        override val key = "collection:${collection.kind}:${collection.id}"
    }
}

fun rankedSearchEntries(query: String, songs: List<Track>, videos: List<Track>,
    artists: List<com.raagmusic.app.data.youtube.ArtistProfile>, collections: List<com.raagmusic.app.data.youtube.BrowseCollection>,
    suggestions: List<String> = emptyList(), providerOrder: List<String> = emptyList()): List<SearchEntry> {
    val order = providerOrder.withIndex().associate { it.value to it.index }
    fun score(entry: SearchEntry): Int = when (entry) {
        is SearchEntry.Song -> searchMatchScore(query, entry.track.title, entry.track.artist)
        is SearchEntry.Video -> searchMatchScore(query, entry.track.title, entry.track.artist)
        is SearchEntry.Artist -> searchMatchScore(query, entry.profile.name)
        is SearchEntry.CollectionResult -> searchMatchScore(query, entry.collection.title, entry.collection.artist)
        is SearchEntry.Suggestion -> searchMatchScore(query, entry.query) - 35
    }
    return (songs.map(SearchEntry::Song) + videos.map(SearchEntry::Video) + artists.map(SearchEntry::Artist) +
        collections.map(SearchEntry::CollectionResult) + suggestions.map(SearchEntry::Suggestion)).distinctBy { it.key }
        .sortedWith(compareByDescending<SearchEntry> { score(it) }.thenBy { order[it.key] ?: Int.MAX_VALUE })
}

/** All is a bounded set of previews; category pages keep their own ordered, paged results. */
data class SearchResultGroup(val kind: String, val title: String, val entries: List<SearchEntry>)

fun groupedSearchResults(query: String, kind: String, songs: List<Track>, videos: List<Track>,
    artists: List<com.raagmusic.app.data.youtube.ArtistProfile>,
    collections: List<com.raagmusic.app.data.youtube.BrowseCollection>, library: List<Track> = emptyList(),
    providerOrder: List<String> = emptyList()): List<SearchResultGroup> {
    val all = kind == "All"
    fun group(type: String, title: String = type, entries: List<SearchEntry>, limit: Int): SearchResultGroup? {
        if (!all && kind != type) return null
        val unique = entries.distinctBy { it.key }
        return unique.take(if (all) limit else unique.size).takeIf { it.isNotEmpty() }?.let { SearchResultGroup(type, title, it) }
    }
    val ranked = if (all) rankedSearchEntries(query, songs, videos, artists, collections, providerOrder = providerOrder) else emptyList()
    fun entries(type: String): List<SearchEntry> = if (all) ranked.filter {
        when (type) {
            "Songs" -> it is SearchEntry.Song
            "Videos" -> it is SearchEntry.Video
            "Artists" -> it is SearchEntry.Artist
            "Albums" -> it is SearchEntry.CollectionResult && it.collection.kind == com.raagmusic.app.data.youtube.CollectionKind.ALBUM
            "Playlists" -> it is SearchEntry.CollectionResult && it.collection.kind == com.raagmusic.app.data.youtube.CollectionKind.PLAYLIST
            else -> false
        }
    } else when (type) {
        "Songs" -> songs.map(SearchEntry::Song)
        "Videos" -> videos.map(SearchEntry::Video)
        "Artists" -> artists.map(SearchEntry::Artist)
        "Albums" -> collections.filter { it.kind == com.raagmusic.app.data.youtube.CollectionKind.ALBUM }.map(SearchEntry::CollectionResult)
        "Playlists" -> collections.filter { it.kind == com.raagmusic.app.data.youtube.CollectionKind.PLAYLIST }.map(SearchEntry::CollectionResult)
        else -> emptyList()
    }
    return listOfNotNull(
        group("Songs", entries = entries("Songs"), limit = 4),
        group("Artists", entries = entries("Artists"), limit = 3),
        group("Albums", entries = entries("Albums"), limit = 3),
        group("Playlists", entries = entries("Playlists"), limit = 3),
        group("Videos", entries = entries("Videos"), limit = 2),
        group("Library", "Your library", library.map(SearchEntry::Song), limit = 3),
    )
}

/** Consume duplicate-only pages briefly, but stop repeating cursors or unbounded empty pagination. */
class SearchPageProgress {
    private val seen = mutableSetOf<String>()
    private var emptyPages = 0
    fun reset() { seen.clear(); emptyPages = 0 }
    fun next(current: String, proposed: String?, addedResults: Boolean): Boolean {
        seen += current
        emptyPages = if (addedResults) 0 else emptyPages + 1
        return proposed != null && proposed !in seen && emptyPages < 3
    }
}
