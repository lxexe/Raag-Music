package com.raagmusic.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raagmusic.app.domain.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.libraryDataStore by preferencesDataStore("raag_library")

/** Atomic DataStore edits prevent lost updates when several screens change the library. */
class LibraryStore(private val context: Context) {
    private val key = stringPreferencesKey("library_v1")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = context.libraryDataStore.data.catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { LibraryCodec.decode(it[key]) }.distinctUntilChanged().flowOn(Dispatchers.Default)
    val observed = state.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    suspend fun update(transform: (LibraryState) -> LibraryState) = withContext(Dispatchers.IO) {
        context.libraryDataStore.edit {
            val previous = LibraryCodec.decode(it[key])
            val next = transform(previous)
            if (next != previous) it[key] = LibraryCodec.encode(next)
        }
    }

}

object LibraryCodec {
    fun decode(raw: String?): LibraryState {
        if (raw == null) return LibraryState()
        val j = JSONObject(raw)
        fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
        val playlists = j.optJSONArray("playlists") ?: JSONArray()
        val tracks = j.optJSONArray("tracks") ?: JSONArray()
        return LibraryState(
            strings(j.optJSONArray("favorites") ?: JSONArray()).toSet(),
            List(playlists.length()) { i -> playlists.getJSONObject(i).let { Playlist(it.getString("id"), it.getString("name"), strings(it.getJSONArray("tracks")), it.optString("description"), it.optString("coverUri"), it.optString("folder")) } },
            List(tracks.length()) { i -> tracks.getJSONObject(i).let { Track(it.getString("id"), it.getString("title"), it.getString("artist"), it.getString("artwork"), it.getString("uri"), Source.valueOf(it.getString("source")), it.optString("mood", ""), it.optLong("durationSeconds", 0), it.optString("album", ""), it.optString("genre", "")) } },
            strings(j.optJSONArray("recent") ?: JSONArray()),
            j.optJSONObject("likedAt")?.let { o -> o.keys().asSequence().associateWith { o.optLong(it).coerceAtLeast(0) } }.orEmpty(),
        )
    }

    fun encode(s: LibraryState) = JSONObject().apply {
        put("favorites", JSONArray(s.favorites.toList()))
        put("recent", JSONArray(s.recentIds))
        put("likedAt", JSONObject(s.likedAt))
        put("playlists", JSONArray(s.playlists.map { JSONObject().put("id", it.id).put("name", it.name).put("tracks", JSONArray(it.trackIds)).put("description", it.description).put("coverUri", it.coverUri).put("folder", it.folder) }))
        put("tracks", JSONArray(s.savedTracks.map { JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("artwork", it.artwork).put("uri", it.uri).put("source", it.source.name).put("mood", it.mood).put("durationSeconds", it.durationSeconds).put("album", it.album).put("genre", it.genre) }))
    }.toString()
}
