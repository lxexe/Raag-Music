package com.raagmusic.app.data.youtube

import org.json.JSONObject
import org.json.JSONArray

data class ArtistProfile(
    val name: String, val imageUrl: String = "", val bannerUrl: String = "",
    val description: String = "", val subscribers: Long? = null,
    val monthlyAudience: String? = null, val channelUrl: String = "", val verified: Boolean = false,
    val albums: List<BrowseCollection> = emptyList(), val popularSongs: List<Track> = emptyList(),
)

/** Only use audience labels attached to an artist header, never song view counts. */
object ArtistProfileParser {
    fun musicHeader(raw: String, fallback: ArtistProfile): ArtistProfile {
        val catalog = MusicSearchParser.parse(raw)
        val enriched = fallback.copy(albums = MusicAlbumParser.parse(raw).map { if (it.artist.isBlank() || it.artist == "YouTube Music") it.copy(artist = fallback.name) else it }, popularSongs = catalog.tracks)
        val root = JSONObject(raw)
        fun find(value: Any?, key: String): JSONObject? = when (value) {
            is JSONObject -> value.optJSONObject(key) ?: value.keys().asSequence().mapNotNull { find(value.opt(it), key) }.firstOrNull()
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { find(value.opt(it), key) }
            else -> null
        }
        fun texts(value: Any?): List<String> = when (value) {
            is JSONObject -> listOfNotNull(value.optString("text").takeIf { it.isNotBlank() },
                value.optString("simpleText").takeIf { it.isNotBlank() }) +
                value.keys().asSequence().filterNot { it == "text" || it == "simpleText" }.flatMap { texts(value.opt(it)) }.toList()
            is JSONArray -> (0 until value.length()).flatMap { texts(value.opt(it)) }
            else -> emptyList()
        }
        fun image(value: Any?): String? = when (value) {
            is JSONObject -> value.optJSONArray("thumbnails")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }.maxByOrNull { it.optInt("width") }?.optString("url")
            } ?: value.keys().asSequence().mapNotNull { image(value.opt(it)) }.firstOrNull()
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { image(value.opt(it)) }
            else -> null
        }
        val header = find(root, "musicImmersiveHeaderRenderer") ?: find(root, "musicVisualHeaderRenderer")
            ?: find(root, "musicResponsiveHeaderRenderer")
            ?: return enriched
        val title = texts(header.opt("title")).joinToString("").ifBlank { fallback.name }
        val audience = Regex("[0-9.,]+\\s*[KMB]?\\s+monthly\\s+(?:listeners|audience|views)", RegexOption.IGNORE_CASE)
            .find(texts(header).joinToString(" "))?.value?.replace(Regex("\\s+"), " ")
        val subscription = find(header.opt("subscriptionButton"), "subscribeButtonRenderer")
        val subscribers = parseCount(texts(subscription?.opt("subscriberCountText")).joinToString(""))
        val canonicalId = subscription?.optString("channelId").orEmpty().takeIf { Regex("UC[A-Za-z0-9_-]{22}").matches(it) }
        val descriptionShelf = find(root, "musicDescriptionShelfRenderer")
        val description = texts(header.opt("description")).joinToString("")
            .ifBlank { texts(descriptionShelf?.opt("description")).joinToString("") }.ifBlank { fallback.description }
        val hero = image(header.opt("thumbnail")).orEmpty()
        return enriched.copy(name = title, imageUrl = hero.ifBlank { fallback.imageUrl }, bannerUrl = hero.ifBlank { fallback.bannerUrl },
            description = description, monthlyAudience = audience?.let { com.raagmusic.app.domain.compactMonthlyAudience(it) } ?: fallback.monthlyAudience,
            subscribers = subscribers ?: fallback.subscribers,
            channelUrl = canonicalId?.let { "https://www.youtube.com/channel/$it" } ?: fallback.channelUrl)
    }

    private fun parseCount(label: String): Long? {
        val match = Regex("^\\s*([0-9][0-9,.]*)\\s*([KMB])?(?:\\s+subscribers)?\\s*$", RegexOption.IGNORE_CASE).find(label) ?: return null
        val count = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val factor = when (match.groupValues[2].uppercase()) { "K" -> 1_000; "M" -> 1_000_000; "B" -> 1_000_000_000; else -> 1 }
        return (count * factor).toLong().takeIf { it >= 0 }
    }
}
