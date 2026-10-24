package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.raagmusic.app.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ListeningStatistics(features: FeatureState, library: LibraryState) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var week by remember(month) { mutableIntStateOf(0) }
    val recap by produceState<ListeningRecap?>(null, features.history, library.savedTracks, month) {
        value = withContext(Dispatchers.Default) { listeningRecap(features, library, month) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Monthly recap", style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Glyph(Icons.Rounded.ChevronLeft, "Previous month") { month = month.minusMonths(1) }
            Text(month.format(DateTimeFormatter.ofPattern("MMM yyyy")))
            IconButton(enabled = month < YearMonth.now(), onClick = { month = month.plusMonths(1) }) { MusicIcon(Icons.Rounded.ChevronRight, "Next month") }
        }
        recap?.let { r ->
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Minutes", r.minutes.toString(), Modifier.fillMaxWidth()); StatTile("Songs", r.songs.toString(), Modifier.fillMaxWidth()); StatTile("Day streak", r.streak.toString(), Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Minutes", r.minutes.toString(), Modifier.weight(1f)); StatTile("Songs", r.songs.toString(), Modifier.weight(1f)); StatTile("Day streak", r.streak.toString(), Modifier.weight(1f))
            }
            if (r.plays == 0 && r.minutes == 0L) Text("Listen to music to build your recap.", color = Muted)
            else {
                Text("Listening by week", style = MaterialTheme.typography.titleMedium)
                val weeks = (1..month.lengthOfMonth()).chunked(7).map { days -> days.sumOf { r.days[it] ?: 0 } }
                val max = weeks.maxOrNull()?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                    weeks.forEachIndexed { i, ms ->
                        Column(Modifier.weight(1f).fillMaxHeight().clickable { week = i }.semantics { contentDescription = "Week ${i + 1}: ${ms / 60_000} minutes"; selected = week == i }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            Box(Modifier.fillMaxWidth().height((ms.toFloat() / max * 110).coerceAtLeast(6f).dp).background(if (week == i) Lavender else Lavender.copy(alpha = .35f), MaterialTheme.shapes.small))
                            Text("W${i + 1}", fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                Text("Week ${week + 1}: ${weeks.getOrElse(week) { 0 } / 60_000} min · ${r.plays} plays this month", color = Muted)
                Text("Top songs", style = MaterialTheme.typography.titleMedium)
                r.topSongs.forEachIndexed { i, song -> RankedBar(i + 1, song, r.topSongs.firstOrNull()?.milliseconds ?: 1) }
                Text("Top artists", style = MaterialTheme.typography.titleMedium)
                r.topArtists.forEachIndexed { i, artist -> RankedBar(i + 1, artist, r.topArtists.firstOrNull()?.milliseconds ?: 1) }
                ShareCardButton(androidx.compose.ui.res.stringResource(com.raagmusic.app.R.string.recap_title), month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), "${r.minutes} minutes · ${r.songs} songs · ${r.plays} plays", r.topArtists.map { it.label })
            }
        } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.glass().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Lavender); Text(label, fontSize = 11.sp, color = Muted)
    }
}
@Composable
private fun RankedBar(rank: Int, item: RankedListen, max: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { Text("$rank. ${item.label}", Modifier.weight(1f), maxLines = 2); Text("${item.milliseconds / 60_000} min", color = Muted, fontSize = 12.sp) }
        LinearProgressIndicator(progress = { (item.milliseconds.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f) }, color = Lavender, modifier = Modifier.fillMaxWidth().height(3.dp))
    }
}

@Composable
fun HistoryCalendar(features: FeatureState, selectedDay: String?, onSelect: (String?) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    val days = remember(features.history, month) { features.history.filter { it.day.startsWith(month.toString()) }.groupBy { it.day }.mapValues { (_, rows) -> rows.sumOf { it.milliseconds } } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Rounded.ChevronLeft, "Earlier history") { month = month.minusMonths(1) }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f))
            IconButton(enabled = month < YearMonth.now(), onClick = { month = month.plusMonths(1) }) { MusicIcon(Icons.Rounded.ChevronRight, "Later history") }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = List(offset) { 0 } + (1..month.lengthOfMonth()).toList()
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val calendarWidth = maxWidth.coerceAtLeast(336.dp)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.width(calendarWidth)) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        cells.chunked(7).forEach { row -> Row(Modifier.width(calendarWidth)) {
            repeat(7) { index ->
                val day = row.getOrElse(index) { 0 }
                if (day == 0) Spacer(Modifier.weight(1f).height(48.dp)) else {
                    val date = month.atDay(day).toString()
                    val minutes = (days[date] ?: 0) / 60_000
                    Box(Modifier.weight(1f).height(48.dp).background(if (selectedDay == date) Lavender.copy(alpha = .16f) else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                        .clickable { onSelect(if (selectedDay == date) null else date) }.semantics { contentDescription = "$date: $minutes listening minutes"; selected = selectedDay == date }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(day.toString(), color = if (selectedDay == date) Lavender else ContentColor); if ((days[date] ?: 0) > 0) Box(Modifier.size(4.dp).background(Lavender, CircleShape)) }
                    }
                }
            }
        } }
        } }
        if (selectedDay != null) TextButton(onClick = { onSelect(null) }) { Text("Show all history") }
    }
}
