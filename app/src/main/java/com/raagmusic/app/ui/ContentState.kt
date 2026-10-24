package com.raagmusic.app.ui

/** A section can fail or refresh independently without hiding other discovery content. */
data class ContentState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

internal data class TrackCatalog(val tracks: List<com.raagmusic.app.domain.Track> = emptyList(),
    val byId: Map<String, com.raagmusic.app.domain.Track> = emptyMap())
data class QueueView(val queueIds: List<String> = emptyList(), val queueIndex: Int = 0, val upcomingIndices: List<Int> = emptyList())
data class LibraryInsights(val smart: Map<com.raagmusic.app.domain.SmartPlaylist, List<com.raagmusic.app.domain.Track>> = emptyMap(),
    val days: List<com.raagmusic.app.domain.HistoryDay> = emptyList())

data class BrowsePageState(
    val title: String, val query: String, val mood: Boolean,
    val songs: ContentState<com.raagmusic.app.domain.Track> = ContentState(loading = true),
    val trending: ContentState<com.raagmusic.app.domain.Track> = ContentState(loading = true),
    val releases: ContentState<com.raagmusic.app.domain.Track> = ContentState(loading = true),
    val playlists: ContentState<com.raagmusic.app.data.youtube.BrowseCollection> = ContentState(loading = true),
    val albums: ContentState<com.raagmusic.app.data.youtube.BrowseCollection> = ContentState(loading = true),
)
