package com.raagmusic.app.domain

/** Entity types share one relevance-ranked feed. */
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
