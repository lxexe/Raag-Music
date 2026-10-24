package com.raagmusic.app.data.youtube

import org.json.JSONArray
import org.json.JSONObject

/** Unfiltered Music search/browse renderers, including the provider's top result. */
object MusicSearchParser {
    fun parse(raw: String): SearchPage {
        val songs = mutableListOf<Track>(); val videos = mutableListOf<Track>()
        val artists = mutableListOf<ArtistProfile>(); val collections = mutableListOf<BrowseCollection>()
        val order = mutableListOf<String>()
        fun read(r: JSONObject) {
            if (r.optString("musicItemRendererDisplayPolicy").endsWith("GREY_OUT")) return
            val columns = r.optJSONArray("flexColumns")
            fun column(index: Int) = columns?.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            val titleObject = column(0) ?: r.optJSONObject("title")
            val title = text(titleObject)
            if (title.isBlank()) return
            val nodes = objects(r).toList()
            val endpoint = r.optJSONObject("navigationEndpoint") ?: objects(titleObject).mapNotNull { it.optJSONObject("navigationEndpoint") }.firstOrNull()
            val browseId = endpoint?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            val image = nodes.flatMap { o -> o.optJSONArray("thumbnails")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            }.orEmpty() }.maxByOrNull { it.optInt("width") }?.optString("url").orEmpty()
            val byline = column(1) ?: r.optJSONObject("subtitle")
            val credits = objects(byline).filter { it.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")?.startsWith("UC") == true }
                .map { it.optString("text") }.filter { it.isNotBlank() }.distinct().toList()
            val artist = credits.joinToString(", ").ifBlank { text(byline).split(" • ", " · ").firstOrNull {
                it.isNotBlank() && it !in setOf("Song", "Video", "Album", "Single", "EP", "Playlist") && !it.contains("views") && !Regex("\\d{4}").matches(it)
            }.orEmpty() }
            when {
                browseId.startsWith("UC") -> {
                    artists += ArtistProfile(title, imageUrl = image, channelUrl = "https://www.youtube.com/channel/$browseId")
                    order += "artist:$browseId"
                }
                browseId.startsWith("MPRE") || browseId.startsWith("VL") -> {
                    val album = browseId.startsWith("MPRE")
                    val id = if (album) browseId else browseId.removePrefix("VL")
                    collections += BrowseCollection(id, title, artist, image,
                        if (album) "https://music.youtube.com/browse/$id" else "https://www.youtube.com/playlist?list=$id", 0,
                        if (album) CollectionKind.ALBUM else CollectionKind.PLAYLIST)
                    order += "collection:${if (album) CollectionKind.ALBUM else CollectionKind.PLAYLIST}:$id"
                }
                else -> {
                    val id = nodes.map { it.optString("videoId") }.firstOrNull { Regex("[A-Za-z0-9_-]{11}").matches(it) } ?: return
                    val video = nodes.any { it.optString("musicVideoType") in setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC") }
                    val duration = objects(r.opt("fixedColumns")).map { text(it) }.firstOrNull { Regex("\\d{1,2}:\\d{2}(?::\\d{2})?").matches(it) }
                        ?: text(byline).split(" • ").firstOrNull { Regex("\\d{1,2}:\\d{2}(?::\\d{2})?").matches(it) }.orEmpty()
                    val seconds = duration.split(':').mapNotNull { it.toLongOrNull() }.fold(0L) { total, part -> total * 60 + part }
                    val track = Track(id, title, artist.ifBlank { "YouTube Music" }, image, seconds)
                    if (video) videos += track else songs += track
                    order += "${if (video) "video" else "track"}:$id"
                }
            }
        }
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    listOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "musicCardShelfRenderer").forEach { value.optJSONObject(it)?.let(::read) }
                    value.keys().forEach { visit(value.opt(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
            }
        }
        visit(JSONObject(raw))
        return SearchPage(songs.distinctBy { it.id }, artists.distinctBy { it.channelUrl }, collections.distinctBy { "${it.kind}:${it.id}" },
            videos = videos.distinctBy { it.id }, order = order.distinct())
    }
    private fun text(value: JSONObject?): String = value?.optString("simpleText").orEmpty().ifBlank {
        val runs = value?.optJSONArray("runs") ?: return@ifBlank ""
        (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }
    private fun objects(value: Any?): Sequence<JSONObject> = sequence {
        when (value) {
            is JSONObject -> { yield(value); value.keys().forEach { yieldAll(objects(value.opt(it))) } }
            is JSONArray -> (0 until value.length()).forEach { yieldAll(objects(value.opt(it))) }
        }
    }
}
