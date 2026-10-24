package com.raagmusic.app.data.youtube

import org.json.JSONArray
import org.json.JSONObject

/** Parse Music's native automix, retaining album/artist bylines when the provider supplies them. */
object MusicRadioParser {
    fun parse(raw: String): List<Track> {
        val tracks = mutableListOf<Track>()
        fun text(value: JSONObject?): String = value?.optString("simpleText").orEmpty().ifBlank {
            val runs = value?.optJSONArray("runs") ?: return@ifBlank ""
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    value.optJSONObject("playlistPanelVideoRenderer")?.let { song ->
                        val id = song.optString("videoId")
                        val title = text(song.optJSONObject("title"))
                        if (Regex("[A-Za-z0-9_-]{11}").matches(id) && title.isNotBlank()) {
                            val byline = song.optJSONObject("longBylineText") ?: song.optJSONObject("shortBylineText")
                            val runs = byline?.optJSONArray("runs") ?: JSONArray()
                            val artists = mutableListOf<String>()
                            var album = ""
                            for (i in 0 until runs.length()) {
                                val run = runs.optJSONObject(i) ?: continue
                                val endpoint = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: continue
                                val type = endpoint.optJSONObject("browseEndpointContextSupportedConfigs")
                                    ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
                                val label = run.optString("text").trim()
                                if (type == "MUSIC_PAGE_TYPE_ALBUM") album = label
                                else if (type == "MUSIC_PAGE_TYPE_ARTIST" || endpoint.optString("browseId").startsWith("UC")) artists += label
                            }
                            val thumbnails = song.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: JSONArray()
                            val thumbnail = (0 until thumbnails.length()).mapNotNull { thumbnails.optJSONObject(it) }
                                .maxByOrNull { it.optInt("width") }?.optString("url").orEmpty()
                            val seconds = text(song.optJSONObject("lengthText")).split(':').mapNotNull { it.toLongOrNull() }
                                .fold(0L) { total, part -> total * 60 + part }
                            tracks += Track(id, title, artists.distinct().joinToString(", "), thumbnail, seconds, album = album)
                        }
                    }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(JSONObject(raw))
        return tracks.distinctBy { it.id }
    }
}
