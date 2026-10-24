package com.raagmusic.app.data.youtube

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.Locale

/** Expiring public metadata only. Never stores stream URLs or user listening data. */
internal class ArtistMetadataStore(private val directory: File?, private val loadImmediately: Boolean = true, private val now: () -> Long = System::currentTimeMillis) {
    private val profiles = LinkedHashMap<String, ArtistProfile>()
    private val credits = LinkedHashMap<String, List<String>>()
    private val dates = mutableMapOf<String, Long>()
    private data class Snapshot(val profiles: Map<String, ArtistProfile>, val credits: Map<String, List<String>>, val dates: Map<String, Long>)
    @Volatile private var snapshot = Snapshot(emptyMap(), emptyMap(), emptyMap())
    private fun publish() { snapshot = Snapshot(profiles.toMap(), credits.toMap(), dates.toMap()) }
    private var loaded = false
    init { if (loadImmediately) load() }
    @Synchronized fun load() {
        if (loaded) return
        loaded = true
        try {
            val file = directory?.resolve("artist-metadata-v1.json")
            if (file != null && file.isFile && file.length() <= 512_000) {
                val root = JSONObject(file.readText())
                val entries = root.optJSONObject("entries") ?: JSONObject()
                entries.keys().forEach { key ->
                    val entry = entries.getJSONObject(key)
                    val date = entry.optLong("date")
                    if (now() - date in 0 until LIFETIME) {
                        dates[key] = date
                        if (key.startsWith("artist:")) profiles[key] = ArtistProfile(entry.getString("name"), entry.optString("image"), channelUrl = entry.optString("channel"))
                        else if (key.startsWith("song:")) credits[key] = entry.getJSONArray("artists").let { a -> (0 until a.length()).map { a.getString(it) } }
                    }
                }
            }
        } catch (_: Exception) { /* Discard a corrupt or outdated expendable cache. */ }
        publish()
    }
    // UI cache reads must not acquire the writer's lock while it parses/writes a disk snapshot.
    fun profile(name: String): ArtistProfile? {
        val view = snapshot; val key = key(name)
        return view.profiles[key]?.takeIf { now() - (view.dates[key] ?: 0) in 0 until LIFETIME }
    }
    fun artists(id: String): List<String>? {
        val view = snapshot; val key = "song:$id"
        return view.credits[key]?.takeIf { now() - (view.dates[key] ?: 0) in 0 until LIFETIME }
    }
    @Synchronized fun put(profile: ArtistProfile) {
        load()
        if (profile.imageUrl.isBlank()) return
        val key = key(profile.name)
        profiles[key] = profile; dates[key] = now(); save()
    }
    @Synchronized fun put(id: String, artists: List<String>) {
        load()
        if (artists.isEmpty()) return
        credits["song:$id"] = artists; dates["song:$id"] = now(); save()
    }
    @Synchronized fun putSongs(songs: List<Track>) {
        load()
        songs.filter { it.artist.isNotBlank() }.forEach { song ->
            val names = ArtistNames.fallback(song.artist)
            if (names.isNotEmpty()) { credits["song:${song.id}"] = names; dates["song:${song.id}"] = now() }
        }
        save()
    }
    private fun key(name: String) = "artist:${name.trim().lowercase(Locale.ROOT)}"
    private fun fresh(key: String) = now() - (dates[key] ?: 0) in 0 until LIFETIME
    private fun save() {
        while (dates.size > 256) {
            val key = dates.minBy { it.value }.key
            dates.remove(key); profiles.remove(key); credits.remove(key)
        }
        publish()
        val entries = JSONObject()
        profiles.forEach { (key, p) -> if (fresh(key)) entries.put(key, JSONObject().put("date", dates[key]).put("name", p.name).put("image", p.imageUrl).put("channel", p.channelUrl)) }
        credits.forEach { (key, names) -> if (fresh(key)) entries.put(key, JSONObject().put("date", dates[key]).put("artists", JSONArray(names))) }
        try {
            val dir = directory ?: return
            dir.mkdirs()
            val temporary = dir.resolve("artist-metadata-v1.tmp")
            temporary.writeText(JSONObject().put("entries", entries).toString())
            temporary.renameTo(dir.resolve("artist-metadata-v1.json"))
        } catch (_: Exception) { /* Disk cache failure must not interrupt playback. */ }
    }
    companion object { const val LIFETIME = 7L * 24 * 60 * 60 * 1000 }
}
