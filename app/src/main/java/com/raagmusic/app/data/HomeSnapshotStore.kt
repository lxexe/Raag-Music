package com.raagmusic.app.data

import com.raagmusic.app.domain.Source
import com.raagmusic.app.domain.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Disposable discovery metadata; never persist audio URLs or another region's feed. */
class HomeSnapshotStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    @Synchronized fun read(country: String, mood: String, taste: String = ""): List<Track> = runCatching {
        if (!file.isFile || file.length() > 128_000) return emptyList()
        val json = JSONObject(file.readText())
        if (json.optString("country") != country || json.optString("mood") != mood || json.optString("taste") != taste ||
            now() - json.optLong("created") !in 0..86_400_000) return emptyList()
        val entries = json.optJSONArray("songs") ?: return emptyList()
        (0 until minOf(entries.length(), 60)).mapNotNull { index ->
            val song = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = song.optString("id")
            val title = song.optString("title").take(300)
            if (!Regex("[A-Za-z0-9_-]{11}").matches(id) || title.isBlank()) return@mapNotNull null
            Track(id, title, song.optString("artist").take(300), song.optString("artwork").take(2_000), id,
                Source.YOUTUBE, durationSeconds = song.optLong("duration").coerceIn(0, 3_600),
                album = song.optString("album").take(300), genre = song.optString("genre").take(100), mood = song.optString("mood").take(100))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    @Synchronized fun write(country: String, mood: String, tracks: List<Track>, taste: String = "") {
        val safe = tracks.filter { it.source == Source.YOUTUBE && Regex("[A-Za-z0-9_-]{11}").matches(it.id) }.distinctBy { it.id }.take(60)
        if (safe.isEmpty()) return
        val songs = JSONArray()
        safe.forEach { song -> songs.put(JSONObject().put("id", song.id).put("title", song.title.take(300))
            .put("artist", song.artist.take(300)).put("artwork", song.artwork.take(2_000)).put("duration", song.durationSeconds)
            .put("album", song.album.take(300)).put("genre", song.genre.take(100)).put("mood", song.mood.take(100))) }
        val json = JSONObject().put("country", country).put("mood", mood).put("taste", taste).put("created", now()).put("songs", songs).toString()
        if (json.toByteArray().size > 128_000) return
        val temporary = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            file.parentFile?.mkdirs()
            temporary.writeText(json)
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }
}
