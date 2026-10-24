package com.raagmusic.app.data.youtube

/** Remote collections contain metadata and a stable YouTube playlist reference only. */
enum class CollectionKind { PLAYLIST, ALBUM }

@androidx.compose.runtime.Immutable
data class BrowseCollection(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val url: String,
    val trackCount: Long,
    val kind: CollectionKind,
)
