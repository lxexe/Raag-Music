package com.raagmusic.app.domain

import java.time.LocalDate
import java.util.Locale

enum class LibrarySort(val label: String) { RECENT("Recently added"), TITLE("Title"), ARTIST("Artist") }
enum class LibraryFilter(val label: String) { ALL("All songs"), FAVORITES("Favorites"), LOCAL("Local / downloaded") }
fun libraryTracks(library: LibraryState, sort: LibrarySort, filter: LibraryFilter, query: String = "", downloaded: Set<String> = emptySet()): List<Track> {
    val tracks = library.savedTracks.asReversed().distinctBy { it.id }.filter { t ->
        (query.isBlank() || t.title.contains(query.trim(), true) || t.artist.contains(query.trim(), true)) && when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.FAVORITES -> t.id in library.favorites
            LibraryFilter.LOCAL -> t.uri.startsWith("content:", true) || t.uri.startsWith("file:", true) || t.id in downloaded
        }
    }
    return when (sort) {
        LibrarySort.RECENT -> tracks
        LibrarySort.TITLE -> tracks.sortedWith(compareBy({ it.title.lowercase(Locale.ROOT) }, { it.artist.lowercase(Locale.ROOT) }, { it.id }))
        LibrarySort.ARTIST -> tracks.sortedWith(compareBy({ it.artist.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }, { it.id }))
    }
}
enum class SmartPlaylist(val label: String, val description: String) {
    RECENT_LIKES("Recently liked", "Your latest favorites, up to 50 songs."),
    MOST_PLAYED("Most played", "Your top 50 songs by recorded plays, then listening time."),
    REDISCOVER("Rediscover", "Favorites and previously played songs you haven't heard in 30 days.")
}
fun smartPlaylist(kind: SmartPlaylist, library: LibraryState, features: FeatureState, today: LocalDate): List<Track> {
    val history = features.history.groupBy { it.trackId }
    val savedOrder = library.savedTracks.mapIndexed { index, track -> track.id to index }.toMap()
    val songs = library.savedTracks.distinctBy { it.id }.filterNot { it.id in features.lessLikeIds }
    return when (kind) {
        SmartPlaylist.RECENT_LIKES -> songs.filter { it.id in library.favorites }.sortedWith(
            compareByDescending<Track> { library.likedAt[it.id] ?: 0L }.thenByDescending { savedOrder[it.id] ?: 0 })
        SmartPlaylist.MOST_PLAYED -> songs.filter { (history[it.id]?.sumOf { it.plays } ?: 0) > 0 }
            .sortedWith(compareByDescending<Track> { history[it.id]?.sumOf { it.plays } ?: 0 }
                .thenByDescending { history[it.id]?.sumOf { it.milliseconds } ?: 0L }.thenBy { it.id })
        SmartPlaylist.REDISCOVER -> songs.filter { t ->
            val last = history[t.id]?.maxOfOrNull { it.day }
            (t.id in library.favorites || last != null) && (last == null || last < today.minusDays(30).toString())
        }.sortedWith(compareBy<Track> { history[it.id]?.maxOfOrNull { row -> row.day }.orEmpty() }.thenBy { it.title })
    }.take(50)
}
data class HistoryDay(val day: String, val tracks: List<Track>, val milliseconds: Long, val plays: Int)
fun historyTimeline(library: LibraryState, features: FeatureState): List<HistoryDay> {
    val metadata = library.savedTracks.associateBy { it.id }
    return features.history.groupBy { it.day }.toSortedMap(reverseOrder()).map { (day, records) ->
        HistoryDay(day, records.sortedWith(compareByDescending<ListenRecord> { it.plays }.thenByDescending { it.milliseconds })
            .mapNotNull { metadata[it.trackId] }.distinctBy { it.id }, records.sumOf { it.milliseconds }, records.sumOf { it.plays })
    }
}
enum class TransitionMode(val label: String) { GAPLESS("Gapless"), CROSSFADE("Crossfade") }
enum class EqualizerPreset(val label: String, val gains: List<Int>) {
    FLAT("Flat", listOf(0, 0, 0, 0, 0)), BASS("Bass", listOf(6, 4, 0, -1, 0)),
    VOCAL("Vocal", listOf(-2, -1, 3, 4, 1)), CUSTOM("Custom", listOf(0, 0, 0, 0, 0))
}
data class EqualizerSettings(val enabled: Boolean = false, val preset: EqualizerPreset = EqualizerPreset.FLAT,
    val customGains: List<Int> = listOf(0, 0, 0, 0, 0)) {
    val gains: List<Int> get() = if (preset == EqualizerPreset.CUSTOM) List(5) { customGains.getOrElse(it) { 0 }.coerceIn(-12, 12) } else preset.gains
}

/** Stable duplicate keys in one pass, even for queues containing the same song many times. */
fun queueOccurrenceKeys(ids: List<String>): List<String> {
    val occurrences = mutableMapOf<String, Int>()
    return ids.map { id -> val ordinal = occurrences[id] ?: 0; occurrences[id] = ordinal + 1; "$id:$ordinal" }
}
