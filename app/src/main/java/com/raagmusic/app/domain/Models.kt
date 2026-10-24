package com.raagmusic.app.domain

enum class Source { DIRECT, YOUTUBE, LOCAL }

@androidx.compose.runtime.Immutable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String,
    val uri: String,
    val source: Source = Source.DIRECT,
    val mood: String = "",
    val durationSeconds: Long = 0,
    val album: String = "",
    val genre: String = "",
) {
    val canDownload: Boolean get() = source != Source.LOCAL
}

data class Playlist(val id: String, val name: String, val trackIds: List<String> = emptyList(),
    val description: String = "", val coverUri: String = "", val folder: String = "") {
    fun add(trackId: String) = if (trackId in trackIds) this else copy(trackIds = trackIds + trackId)
    fun remove(trackId: String) = copy(trackIds = trackIds - trackId)
}

data class LibraryState(
    val favorites: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val savedTracks: List<Track> = emptyList(),
    val recentIds: List<String> = emptyList(),
    val likedAt: Map<String, Long> = emptyMap(),
)

/** Hydrate existing records without losing richer credits or browse context on a later sparse result. */
fun rememberTrackMetadata(existing: List<Track>, incoming: List<Track>): List<Track> {
    val tracks = existing.associateByTo(linkedMapOf()) { it.id }
    incoming.forEach { next ->
        val previous = tracks[next.id]
        tracks[next.id] = if (previous == null) next else next.copy(
            artist = next.artist.ifBlank { previous.artist }, artwork = next.artwork.ifBlank { previous.artwork },
            album = next.album.ifBlank { previous.album }, genre = next.genre.ifBlank { previous.genre },
            mood = next.mood.ifBlank { previous.mood },
            durationSeconds = next.durationSeconds.takeIf { it > 0 } ?: previous.durationSeconds,
        )
    }
    return tracks.values.toList()
}
