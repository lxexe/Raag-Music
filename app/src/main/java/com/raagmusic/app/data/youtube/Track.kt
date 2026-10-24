package com.raagmusic.app.data.youtube

/** Persistent metadata only. A signed playback URL never belongs in this model. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val durationSeconds: Long,
    val album: String = "",
    val genre: String = "",
    val mood: String = "",
)

/** Preserve the existing Compose-facing model without coupling the extractor to the UI. */
fun Track.toLibraryTrack() = com.raagmusic.app.domain.Track(
    id = id,
    title = title,
    artist = artist,
    artwork = thumbnailUrl,
    uri = id, // Stable video ID, NOT an expiring googlevideo URL.
    source = com.raagmusic.app.domain.Source.YOUTUBE,
    durationSeconds = durationSeconds,
    album = album, genre = genre, mood = mood,
)
