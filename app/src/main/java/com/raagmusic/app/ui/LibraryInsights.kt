package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@UnstableApi
@Composable
internal fun rememberListeningAnalytics(features: FeatureState, library: LibraryState, range: AnalyticsRange): ListeningAnalytics {
    val today = LocalDate.now()
    val analytics by produceState(ListeningAnalytics(), features.history, library.savedTracks, range, today) {
        value = withContext(Dispatchers.Default) { listeningAnalytics(features.history, library.savedTracks, range, today) }
    }
    return analytics
}

@UnstableApi
internal fun LazyListScope.libraryInsights(vm: MusicViewModel, features: FeatureState, library: LibraryState,
    analytics: ListeningAnalytics, range: AnalyticsRange, onRange: (AnalyticsRange) -> Unit,
    onSettings: () -> Unit, onMore: (Track) -> Unit) {
    val metadata = library.savedTracks.associateBy { it.id }
    val recent = library.recentIds.mapNotNull(metadata::get).take(20)
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your listening", style = MaterialTheme.typography.headlineMedium)
                Text("Music, through your habits", style = MaterialTheme.typography.bodyMedium, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
            Glyph(Icons.Rounded.Settings, "Insights settings", onClick = onSettings)
        }
        Row(Modifier.padding(top = 20.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalyticsRange.entries.forEach { choice -> Pill(choice.label, range == choice) { onRange(choice) } }
        }
    }
    if (!features.preferences.analyticsEnabled) item {
        Surface(shape = RoundedCornerShape(20.dp), color = Glass) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Analytics are paused", style = MaterialTheme.typography.titleMedium)
                Text("Your saved activity remains available. Enable analytics to record new listening.", color = Muted)
                TextButton(onClick = { vm.updatePreferences { it.copy(analyticsEnabled = true) } }) { Text("Enable analytics") }
            }
        }
    }
    item {
        Column(Modifier.fillMaxWidth().glass().padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("LISTENING TIME", style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(listeningDuration(analytics.milliseconds), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
            Text("${countLabel(analytics.plays, "play")} · ${countLabel(analytics.songs, "song")} · ${countLabel(analytics.days, "active day")}", color = Muted)
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BehaviorMetric("Early skips", "${analytics.skipPercent}%", countLabel(analytics.skips, "skip"), Modifier.weight(1f))
            BehaviorMetric("Average listen", listeningDuration(analytics.averageListenMs), "Per song start", Modifier.weight(1f))
        }
    }
    if (analytics.plays > 0 || analytics.milliseconds > 0) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Listening activity", style = MaterialTheme.typography.titleLarge)
                val peak = analytics.activity.maxOfOrNull { it.milliseconds }?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    analytics.activity.forEach { bucket ->
                        Column(Modifier.weight(1f).semantics { contentDescription = "${bucket.start} to ${bucket.end}: ${listeningDuration(bucket.milliseconds)}" },
                            verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height((bucket.milliseconds.toFloat() / peak * 110).coerceAtLeast(4f).dp).background(ContentColor, RoundedCornerShape(5.dp)))
                            Text(bucket.start.format(DateTimeFormatter.ofPattern("d MMM")), fontSize = 9.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        item { AnalyticsRanking("Top songs", analytics.topSongs) }
        item { AnalyticsRanking("Top artists", analytics.topArtists) }
    } else item { Text("Play a song to start building your listening story.", color = Muted) }
    item { ListeningStatistics(features, library) }
    item { Text("Recently played", style = MaterialTheme.typography.titleLarge) }
    if (recent.isEmpty()) item { Text("Songs you play will appear here.", color = Muted) }
    items(recent, key = { "recent-${it.id}" }) { track ->
        TrackRow(track, showFavorite = false, onPlay = { vm.play(track, recent) }, onFavorite = { vm.favorite(track) }, onMore = { onMore(track) })
    }
    item { Text("Stored on this device. Turning analytics off stops new statistics; recent history has its own setting. All time includes retained activity.", style = MaterialTheme.typography.bodySmall, color = Muted) }
}

private fun countLabel(count: Int, label: String) = "$count $label${if (count == 1) "" else "s"}"

private fun listeningDuration(ms: Long): String {
    val seconds = ms / 1000
    return when { seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"; seconds >= 60 -> "${seconds / 60}m"; else -> "${seconds}s" }
}
@Composable
private fun BehaviorMetric(title: String, value: String, subtitle: String, modifier: Modifier) {
    Column(modifier.glass().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun AnalyticsRanking(title: String, entries: List<AnalyticsRank>) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        entries.take(5).forEachIndexed { index, entry ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${index + 1}", color = Muted)
                    Text(entry.label, Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.SemiBold)
                    Text(listeningDuration(entry.milliseconds), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(progress = { entry.milliseconds.toFloat() / (entries.firstOrNull()?.milliseconds ?: 1).coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(3.dp), color = ContentColor, trackColor = Glass)
            }
        }
    }
}
