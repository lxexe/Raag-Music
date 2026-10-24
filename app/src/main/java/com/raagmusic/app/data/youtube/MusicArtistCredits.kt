package com.raagmusic.app.data.youtube

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Read individual artist links from this song's byline, never album/playlist titles. */
object MusicArtistCredits {
    private fun songs(raw: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("playlistPanelVideoRenderer")?.let { result += it }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(JSONObject(raw))
        return result
    }
    fun parse(raw: String, trackId: String): List<String> = songs(raw)
        .firstOrNull { it.optString("videoId") == trackId }?.let(::artists).orEmpty()
    fun related(raw: String, trackId: String): List<String> = songs(raw)
        .filter { it.optString("videoId") != trackId }.flatMap(::artists).distinctBy { it.lowercase(Locale.ROOT) }
    fun linked(raw: String, trackId: String): List<ArtistProfile> = songs(raw)
        .firstOrNull { it.optString("videoId") == trackId }?.let(::links).orEmpty()
        .filter { Regex("https://www.youtube.com/channel/UC[A-Za-z0-9_-]{22}").matches(it.channelUrl) }
    private fun artists(song: JSONObject): List<String> = links(song).map { it.name }
    private fun links(song: JSONObject): List<ArtistProfile> {
        val byline = song.optJSONObject("longBylineText") ?: song.optJSONObject("shortBylineText") ?: return emptyList()
        val runs = byline.optJSONArray("runs") ?: return emptyList()
        return (0 until runs.length()).mapNotNull { index ->
            val run = runs.optJSONObject(index) ?: return@mapNotNull null
            val endpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val artistPage = endpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
            val id = endpoint?.optString("browseId").orEmpty()
            val name = run.optString("text").trim()
            if (name.isNotBlank() && id.startsWith("UC") &&
                (artistPage == "MUSIC_PAGE_TYPE_ARTIST" || artistPage.isNullOrBlank()))
                ArtistProfile(name, channelUrl = "https://www.youtube.com/channel/$id") else null
        }.distinctBy { it.channelUrl }
    }

}

object ArtistNames {
    private fun normalized(name: String) = name.trim().removeSuffix(" - Topic").lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
    fun same(first: String, second: String) = normalized(first) == normalized(second)
    /** Commas and featured-credit labels are safe fallback separators; keep band names containing & intact. */
    fun fallback(byline: String): List<String> = byline.split(Regex(",\\s*|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
}
