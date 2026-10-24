package com.raagmusic.app.data.youtube

import org.json.JSONArray
import org.json.JSONObject

/** Persist provider candidates, then rerank using current preferences; never cache a signed URI. */
internal object TrackMetadataJson {
    fun encode(tracks: List<Track>) = JSONArray().apply {
        tracks.distinctBy { it.id }.take(60).forEach { track -> put(JSONObject().put("id", track.id).put("title", track.title.take(300))
            .put("artist", track.artist.take(300)).put("artwork", track.thumbnailUrl.take(2_000)).put("duration", track.durationSeconds)
            .put("album", track.album.take(300)).put("genre", track.genre.take(100)).put("mood", track.mood.take(100))) }
    }
    fun decode(array: JSONArray): List<Track> = (0 until minOf(array.length(), 60)).mapNotNull { index ->
        val value = array.optJSONObject(index) ?: return@mapNotNull null
        val id = value.optString("id")
        if (runCatching { YoutubeTrackIds.requireValid(id) }.isFailure) return@mapNotNull null
        val title = value.optString("title").take(300).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Track(id, title, value.optString("artist").take(300), value.optString("artwork").take(2_000),
            value.optLong("duration").coerceIn(0, 3_600), value.optString("album").take(300),
            value.optString("genre").take(100), value.optString("mood").take(100))
    }.distinctBy { it.id }
}
