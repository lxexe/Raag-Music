package com.raagmusic.app.data.youtube

import org.json.JSONArray
import org.json.JSONObject

/** Music album renderers can expose a browse endpoint instead of a playlist URL. */
object MusicAlbumParser {
    fun parse(raw: String): List<BrowseCollection> {
        val root = JSONObject(raw)
        val renderers = objects(root).mapNotNull { it.optJSONObject("musicResponsiveListItemRenderer") ?: it.optJSONObject("musicTwoRowItemRenderer") }.toList()
        return renderers.mapNotNull { r ->
            if (r.optString("musicItemRendererDisplayPolicy").endsWith("GREY_OUT")) return@mapNotNull null
            val nested = objects(r).toList()
            val columns = r.optJSONArray("flexColumns")
            val titleText = columns?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text") ?: r.optJSONObject("title")
            val endpoint = r.optJSONObject("navigationEndpoint") ?: objects(titleText).mapNotNull { it.optJSONObject("navigationEndpoint") }.firstOrNull()
            val browseId = endpoint?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            if (!Regex("MPRE[A-Za-z0-9_-]{6,196}").matches(browseId)) return@mapNotNull null
            val title = text(titleText)
            if (title.isBlank()) return@mapNotNull null
            val description = columns?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text") ?: r.optJSONObject("subtitle")
            val runs = description?.optJSONArray("runs") ?: JSONArray()
            val artist = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }.firstOrNull { run ->
                run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.startsWith("UC") == true
            }?.optString("text") ?: (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text") }
                .firstOrNull { it.isNotBlank() && it !in setOf("Album", "Single", "EP", " • ", " · ") && !Regex("\\d{4}").matches(it) }.orEmpty()
            val thumbnails = nested.flatMap { o -> o.optJSONArray("thumbnails")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty() }
            val artwork = thumbnails.maxByOrNull { it.optInt("height") }?.optString("url").orEmpty()
            BrowseCollection(browseId, title, artist.ifBlank { "YouTube Music" }, artwork, "https://music.youtube.com/browse/$browseId", 0, CollectionKind.ALBUM)
        }.distinctBy { it.id }
    }
    private fun text(o: JSONObject?): String {
        val runs = o?.optJSONArray("runs") ?: return o?.optString("simpleText").orEmpty()
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }
    private fun objects(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONObject -> { yield(value); value.keys().forEach { key -> yieldAll(objects(value.opt(key))) } }
            is JSONArray -> for (i in 0 until value.length()) yieldAll(objects(value.opt(i)))
        }
    }
}
