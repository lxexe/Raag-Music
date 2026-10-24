package com.raagmusic.app.data

import com.raagmusic.app.domain.*
import com.raagmusic.app.data.youtube.YoutubeTrackIds
import org.json.JSONObject
import java.net.URI

/** Portable library metadata only: no stream leases, history, account data or audio files. */
object LibraryBackup {
    const val MAX_BYTES = 5 * 1024 * 1024
    fun export(library: LibraryState): String = JSONObject().apply {
        put("format", "raag-library"); put("version", 1)
        // Document grants belong to this installation and cannot be transferred to another phone.
        val portable = library.savedTracks.filter { it.source != Source.LOCAL }
        val localIds = library.savedTracks.filter { it.source == Source.LOCAL }.map { it.id }.toSet()
        put("library", JSONObject(LibraryCodec.encode(library.copy(savedTracks = portable,
            favorites = library.favorites - localIds, recentIds = library.recentIds.filterNot { it in localIds },
            likedAt = library.likedAt.filterKeys { it !in localIds },
            playlists = library.playlists.map { it.copy(trackIds = it.trackIds.filterNot { id -> id in localIds }, coverUri = it.coverUri.takeIf { uri -> uri.startsWith("https://") }.orEmpty()) }))))
    }.toString(2)
    fun parse(raw: String): LibraryState {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup is too large (maximum 5 MB)." }
        val j = JSONObject(raw)
        require(j.optString("format") == "raag-library" && j.optInt("version") == 1) { "Unsupported Raag backup format." }
        val library = LibraryCodec.decode(j.getJSONObject("library").toString())
        require(library.savedTracks.size <= 10_000 && library.playlists.size <= 1000) { "Backup contains too many items." }
        require(library.savedTracks.map { it.id }.distinct().size == library.savedTracks.size &&
            library.playlists.map { it.id }.distinct().size == library.playlists.size) { "Backup contains duplicate identifiers." }
        val ids = library.savedTracks.map { it.id }.toSet()
        library.savedTracks.forEach { t ->
            require(t.id.isNotBlank() && t.id.length <= 200 && t.title.length <= 1000 && t.artist.length <= 1000 &&
                t.durationSeconds >= 0 && t.artwork.length <= 4096 && t.uri.length <= 8192) { "Backup contains invalid track metadata." }
            if (t.artwork.isNotEmpty()) require(URI(t.artwork).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }) { "Invalid artwork URL." }
            when (t.source) {
                Source.YOUTUBE -> { YoutubeTrackIds.requireValid(t.id); require(t.uri == t.id) { "Backup must use stable YouTube IDs." } }
                Source.DIRECT -> require(URI(t.uri).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }) { "Only HTTPS direct audio can be restored." }
                Source.LOCAL -> error("Local files must be imported on this device.")
            }
        }
        library.playlists.forEach { p -> require(p.id.isNotBlank() && p.id.length <= 200 && p.name.isNotBlank() &&
            p.name.length <= 1000 && p.description.length <= 2000 && p.folder.length <= 80 && p.coverUri.length <= 4096 &&
            (p.coverUri.isBlank() || URI(p.coverUri).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }) &&
            p.trackIds.size <= 10_000 && p.trackIds.all { it in ids }) { "Invalid playlist in backup." } }
        require(library.favorites.all { it in ids } && library.recentIds.all { it in ids }) { "Backup references missing tracks." }
        require(library.likedAt.size <= 10_000 && library.likedAt.all { (id, time) -> id in library.favorites && time >= 0 }) { "Invalid favorite dates in backup." }
        return library
    }
    fun merge(current: LibraryState, imported: LibraryState): LibraryState = current.copy(
        savedTracks = (current.savedTracks + imported.savedTracks).distinctBy { it.id },
        favorites = current.favorites + imported.favorites,
        playlists = current.playlists.map { p -> imported.playlists.find { it.id == p.id }?.let { p.copy(trackIds = (p.trackIds + it.trackIds).distinct()) } ?: p } +
            imported.playlists.filterNot { p -> current.playlists.any { it.id == p.id } }.map { it.copy(trackIds = it.trackIds.distinct()) },
        recentIds = (current.recentIds + imported.recentIds).distinct().take(100),
        likedAt = imported.likedAt + current.likedAt,
    )
}
