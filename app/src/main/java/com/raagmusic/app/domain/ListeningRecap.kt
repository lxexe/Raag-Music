package com.raagmusic.app.domain

import java.time.LocalDate
import java.time.YearMonth

data class RankedListen(val label: String, val milliseconds: Long, val trackId: String? = null)
data class ListeningRecap(val minutes: Long, val plays: Int, val songs: Int, val streak: Int,
    val days: Map<Int, Long>, val topSongs: List<RankedListen>, val topArtists: List<RankedListen>)

fun listeningRecap(features: FeatureState, library: LibraryState, month: YearMonth, today: LocalDate = LocalDate.now()): ListeningRecap {
    val records = features.history.filter { it.day.startsWith(month.toString()) }
    val tracks = library.savedTracks.associateBy { it.id }
    val bySong = records.groupBy { it.trackId }.mapValues { (_, rows) -> rows.sumOf { it.milliseconds } }
    val topSongs = bySong.entries.sortedByDescending { it.value }.take(5).map { RankedListen(tracks[it.key]?.title ?: "Unavailable song", it.value, it.key) }
    val byArtist = bySong.entries.groupBy { tracks[it.key]?.artist ?: "Unknown artist" }.mapValues { (_, songs) -> songs.sumOf { it.value } }
    val days = records.groupBy { it.day.takeLast(2).toIntOrNull() ?: 0 }.filterKeys { it in 1..month.lengthOfMonth() }.mapValues { (_, rows) -> rows.sumOf { it.milliseconds } }
    val listenedDays = features.history.filter { it.milliseconds > 0 }.mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.toSet()
    var date = if (today in listenedDays) today else today.minusDays(1)
    var streak = 0
    while (date in listenedDays) { streak++; date = date.minusDays(1) }
    return ListeningRecap(records.sumOf { it.milliseconds } / 60_000, records.sumOf { it.plays }, bySong.size, streak, days, topSongs,
        byArtist.entries.sortedByDescending { it.value }.take(5).map { RankedListen(it.key, it.value) })
}
