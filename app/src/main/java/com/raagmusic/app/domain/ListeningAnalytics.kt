package com.raagmusic.app.domain

import java.time.LocalDate
import kotlin.math.max

enum class AnalyticsRange(val label: String, val days: Long?) {
    DAY("1d", 1), WEEK("7d", 7), MONTH("30d", 30), QUARTER("90d", 90), YEAR("1y", 365), ALL("All time", null)
}
data class AnalyticsRank(val id: String, val label: String, val milliseconds: Long, val plays: Int)
data class ActivityBucket(val start: LocalDate, val end: LocalDate, val milliseconds: Long)
data class ListeningAnalytics(
    val milliseconds: Long = 0, val plays: Int = 0, val skips: Int = 0, val songs: Int = 0, val days: Int = 0,
    val topSongs: List<AnalyticsRank> = emptyList(), val topArtists: List<AnalyticsRank> = emptyList(),
    val activity: List<ActivityBucket> = emptyList(),
) {
    val skipPercent: Int get() = if (plays == 0) 0 else (skips * 100L / plays).toInt().coerceIn(0, 100)
    val averageListenMs: Long get() = milliseconds / plays.coerceAtLeast(1)
}
fun listeningAnalytics(history: List<ListenRecord>, tracks: List<Track>, range: AnalyticsRange, today: LocalDate): ListeningAnalytics {
    val oldest = range.days?.let { today.minusDays(it - 1) }
    val rows = history.mapNotNull { row -> runCatching { LocalDate.parse(row.day) }.getOrNull()?.let { it to row } }
        .filter { (day, _) -> day <= today && (oldest == null || day >= oldest) }
    val metadata = tracks.associateBy { it.id }
    val songs = rows.map { it.second }.groupBy { it.trackId }.map { (id, entries) ->
        AnalyticsRank(id, metadata[id]?.title ?: "Unknown song", entries.sumOf { it.milliseconds }, entries.sumOf { it.plays })
    }.sortedByDescending { it.milliseconds }
    val artists = songs.groupBy { metadata[it.id]?.artist?.takeIf(String::isNotBlank) ?: "Unknown artist" }
        .map { (name, entries) -> AnalyticsRank(name, name, entries.sumOf { it.milliseconds }, entries.sumOf { it.plays }) }.sortedByDescending { it.milliseconds }
    val first = oldest ?: rows.minOfOrNull { it.first } ?: today
    val dayCount = max(1, java.time.temporal.ChronoUnit.DAYS.between(first, today).toInt() + 1)
    val width = (dayCount + 6) / 7
    val activity = (0 until minOf(7, dayCount)).map { i ->
        val start = first.plusDays((i * width).toLong())
        val end = minOf(today, start.plusDays(width.toLong() - 1))
        ActivityBucket(start, end, rows.filter { it.first in start..end }.sumOf { it.second.milliseconds })
    }.filter { it.start <= today }
    return ListeningAnalytics(rows.sumOf { it.second.milliseconds }, rows.sumOf { it.second.plays }, rows.sumOf { it.second.skips },
        songs.size, rows.map { it.first }.distinct().size, songs.take(10), artists.take(10), activity)
}
