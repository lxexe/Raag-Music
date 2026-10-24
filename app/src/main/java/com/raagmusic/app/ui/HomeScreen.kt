package com.raagmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.data.youtube.BrowseCollection
import com.raagmusic.app.domain.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.util.Locale

/** Only alpha/translation animate, in the graphics layer. Lazy rows never remeasure per frame. */
@Composable
internal fun EnterHomeSection(key: String, content: @Composable ColumnScope.() -> Unit) {
    val reduceMotion = LocalAppearance.current.reduceMotion
    var appeared by rememberSaveable(key) { mutableStateOf(false) }
    val progress = remember { Animatable(if (appeared || reduceMotion) 1f else 0f) }
    val distance = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) progress.snapTo(1f) else progress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        appeared = true
    }
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * distance },
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

@Composable
internal fun HomeHeading(title: String, subtitle: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.4).sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action, fontSize = 11.sp) }
    }
}

@Composable
internal fun HomeSpotlight(track: Track, country: String, onPlay: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(206.dp).clip(MaterialTheme.shapes.extraLarge).clickable(onClick = onPlay)) {
        CoverImage(track.artwork, null, Modifier.matchParentSize(), highQuality = true)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xFF11121E).copy(alpha = 0.92f), Color.Black.copy(alpha = 0.65f)))))
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MusicIcon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFE1D6FF), modifier = Modifier.size(15.dp))
                Text("IN THE SPOTLIGHT", color = Color(0xFFE1D6FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Column(Modifier.fillMaxWidth(0.82f)) {
                Text(track.homeTitle(), color = Color.White, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Discover $country", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(shape = CircleShape, color = Color.White) {
                    MusicIcon(Icons.Rounded.PlayArrow, "Play spotlight", tint = Color(0xFF15131F), modifier = Modifier.padding(11.dp).size(26.dp))
                }
            }
        }
    }
}

@UnstableApi
@Composable
internal fun ContinueListeningCard(vm: MusicViewModel) {
    // Progress stays inside this card; the home feed does not recompose on playback ticks.
    val progressFlow = remember(vm) { vm.playback.map { it.copy(position = it.position / 1000 * 1000) }.distinctUntilChanged() }
    val playback by progressFlow.collectAsStateWithLifecycle(initialValue = vm.playback.value)
    val current by vm.currentTrack.collectAsStateWithLifecycle()
    val snapshot by vm.resumeQueue.collectAsStateWithLifecycle()
    val resumed = snapshot.tracks.getOrNull(snapshot.index)
    val track = current ?: resumed
    if (track == null) {
        HomeEmptyInset("Your next chapter starts here", "Play a song and come back anytime.", Icons.Rounded.Headphones)
        return
    }
    val position = if (current != null) playback.position else snapshot.positionMs
    val duration = if (current != null && playback.duration > 0) playback.duration else track.durationSeconds * 1000
    Column(Modifier.padding(horizontal = 22.dp).fillMaxWidth().glass(22).clickable {
        if (current != null) vm.player.toggle() else vm.play(track, snapshot.tracks, snapshot.positionMs)
    }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(track, Modifier.size(62.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(track.homeTitle(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${position / 60_000}:${(position / 1000 % 60).toString().padStart(2, '0')} into your session", color = Lavender, fontSize = 10.sp)
            }
            MusicIcon(if (current != null && playback.playing) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircleFilled,
                if (current != null && playback.playing) "Pause session" else "Resume session", tint = Lavender, modifier = Modifier.size(38.dp))
        }
        LinearProgressIndicator(progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = Lavender, trackColor = Lavender.copy(alpha = 0.12f))
    }
}

@Composable
internal fun HomeTrackContent(state: ContentState<Track>, retry: () -> Unit, content: @Composable () -> Unit) {
    when {
        state.items.isNotEmpty() -> {
            content()
            if (state.error != null) Text("Couldn't update. Showing your last discoveries.", color = Muted, fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 22.dp))
        }
        state.loading -> Box(Modifier.padding(horizontal = 22.dp)) { ShelfSkeletons() }
        else -> Box(Modifier.padding(horizontal = 22.dp)) { SectionFailure(state.error ?: "No music found yet. Try a fresh discovery.", retry) }
    }
}

@Composable
internal fun HomeSongShelf(tracks: List<Track>, ranked: Boolean = false, onPlay: (Track) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(tracks, key = { _, track -> track.id }, contentType = { _, _ -> "song-card" }) { index, track ->
            Column(Modifier.width(160.dp).clickable(onClickLabel = "Play ${track.title}") { onPlay(track) }) {
                Box(Modifier.size(160.dp).clip(RoundedCornerShape(6.dp)).background(Glass)) {
                    CoverImage(track.artwork, null, Modifier.matchParentSize(), rounded = false)
                    if (ranked) Text((index + 1).toString().padStart(2, '0'), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.padding(10.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp))
                }
                Text(track.homeTitle(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
                Text(listOf("Song", track.artist).filter { it.isNotBlank() }.joinToString(" • "), color = Muted, fontSize = 12.sp,
                    lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
internal fun HomeCollectionShelf(state: ContentState<BrowseCollection>, onOpen: (BrowseCollection) -> Unit, retry: () -> Unit) {
    when {
        state.items.isNotEmpty() -> LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(state.items, key = { it.id }, contentType = { "collection-card" }) { CollectionCard(it) { onOpen(it) } }
        }
        state.loading -> Box(Modifier.padding(horizontal = 22.dp)) { ShelfSkeletons() }
        else -> Box(Modifier.padding(horizontal = 22.dp)) { SectionFailure(state.error ?: "No collections found. Try again in a moment.", retry) }
    }
}

@Composable
fun RecentSearchShelf(searches: List<String>, horizontalPadding: Dp = 22.dp, onRemove: ((String) -> Unit)? = null, onSearch: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = horizontalPadding), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        items(searches, key = { it.lowercase(Locale.ROOT) }, contentType = { "search-chip" }) { query ->
            Surface(onClick = { onSearch(query) }, shape = CircleShape, color = Glass) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MusicIcon(Icons.Rounded.History, null, tint = Muted, modifier = Modifier.size(15.dp))
                    Text(query, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 7.dp).widthIn(max = 180.dp))
                    onRemove?.let { remove -> Glyph(Icons.Rounded.Close, "Remove recent search $query") { remove(query) } }
                }
            }
        }
    }
}

@Composable
internal fun HomeEmptyInset(title: String, subtitle: String, icon: ImageVector) {
    Box(Modifier.padding(horizontal = 22.dp)) { HomeEmpty(title, subtitle, icon) }
}

@Composable
private fun HomeEmpty(title: String, subtitle: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().glass(22).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        MusicIcon(icon, null, tint = Lavender, modifier = Modifier.size(26.dp))
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
    }
}

internal data class HomeCategory(val title: String, val detail: String, val query: String, val icon: ImageVector, val color: Long)
internal val genreCategories = listOf(
    HomeCategory("Pop", "On repeat", "pop songs", Icons.Rounded.AutoAwesome, 0xFF7655B6),
    HomeCategory("Hip-hop", "Feel the rhythm", "hip hop songs", Icons.Rounded.GraphicEq, 0xFF996147),
    HomeCategory("Indie", "Something different", "indie songs", Icons.Rounded.Spa, 0xFF43786A),
    HomeCategory("Electronic", "After hours", "electronic dance songs", Icons.Rounded.Bolt, 0xFF4964A4),
    HomeCategory("Rock", "Turn it up", "rock songs", Icons.Rounded.Headphones, 0xFF925C79),
    HomeCategory("R&B", "Soul & groove", "rnb songs", Icons.Rounded.NightsStay, 0xFF796245),
    HomeCategory("Classical", "Timeless", "classical music", Icons.Rounded.Piano, 0xFF577484),
)
internal val moodCategories = listOf(
    HomeCategory("Feel good", "A little sunshine", "happy feel good music", Icons.Rounded.WbSunny, 0xFF8C703C),
    HomeCategory("Workout", "Find your pace", "workout music", Icons.Rounded.FitnessCenter, 0xFF975A4B),
    HomeCategory("Romance", "All the feelings", "romantic songs", Icons.Rounded.FavoriteBorder, 0xFF93546C),
    HomeCategory("Party", "Bring everyone together", "party dance music", Icons.Rounded.Celebration, 0xFF7655B6),
    HomeCategory("Sleep", "Drift into the night", "sleep ambient music", Icons.Rounded.Bedtime, 0xFF486E96),
    HomeCategory("Commute", "Soundtrack your journey", "road trip music", Icons.Rounded.DirectionsCar, 0xFF43786A),
    HomeCategory("Melancholy", "Let it all out", "sad emotional songs", Icons.Rounded.WaterDrop, 0xFF546E86),
    HomeCategory("Unwind", "A softer landing", "relaxing acoustic music", Icons.Rounded.Spa, 0xFF546E56),
    HomeCategory("Throwback", "Take a trip back", "nostalgic throwback songs", Icons.Rounded.History, 0xFF996147),
    HomeCategory("Late night", "One more song", "late night music", Icons.Rounded.NightsStay, 0xFF64518E),
)

@Composable
internal fun CategoryShelf(categories: List<HomeCategory>, onSelect: (HomeCategory) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(categories, key = { it.title }, contentType = { "category" }) { category ->
            val color = Color(category.color)
            Box(Modifier.width(154.dp).height(112.dp).clip(MaterialTheme.shapes.large)
                .background(Brush.linearGradient(listOf(color.copy(red = color.red * 0.85f, green = color.green * 0.85f, blue = color.blue * 0.85f),
                    color.copy(red = color.red * 0.65f, green = color.green * 0.65f, blue = color.blue * 0.65f))))
                .clickable { onSelect(category) }.padding(16.dp)) {
                MusicIcon(category.icon, null, tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.align(Alignment.TopEnd).size(43.dp))
                Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(category.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text(category.detail, color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeCountryPicker(region: MusicRegion?, override: String?, onSelect: (String?) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val locale = Locale.getDefault()
    val countries = remember(locale) { Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(locale) }.sortedBy { it.second } }
    val filtered = remember(countries, query) { countries.filter { it.second.contains(query, true) || it.first.equals(query, true) } }
    RaagSheet(onDismissRequest = onClose, containerColor = Ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ThemeSheetSystemBars()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your music, your region", fontWeight = FontWeight.Bold, fontSize = 23.sp)
            Text("Choose the country for home discoveries. Automatic uses your mobile network, SIM or device region.", color = Muted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().glass(18).clickable { onSelect(null); onClose() }.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                MusicIcon(Icons.Rounded.Public, null, tint = Lavender)
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text("Automatic", fontWeight = FontWeight.SemiBold)
                    Text(if (override == null) "${region?.displayName() ?: "Device region"} · ${region?.source?.label.orEmpty()}" else "Detect from this device", color = Muted, fontSize = 11.sp)
                }
                if (override == null) MusicIcon(Icons.Rounded.Check, "Selected", tint = Lavender)
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Find a country") },
                leadingIcon = { MusicIcon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 370.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filtered, key = { it.first }) { (code, name) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSelect(code); onClose() }.padding(vertical = 15.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(MusicRegion(code).flag, fontSize = 22.sp)
                        Text(name, Modifier.weight(1f).padding(start = 14.dp), fontSize = 14.sp)
                        if (override == code) MusicIcon(Icons.Rounded.Check, "Selected", tint = Lavender)
                    }
                }
                if (filtered.isEmpty()) item { Text("No countries found", color = Muted, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

/** Hide provider video boilerplate on discovery cards without altering stored metadata. */
internal fun Track.homeTitle(): String {
    val prefix = listOf("$artist - ", "$artist – ").firstOrNull { title.startsWith(it, ignoreCase = true) }
    val display = if (prefix == null) title else title.substring(prefix.length)
    return display.replace(Regex("\\s*[\\[(](?:official(?:\\s+(?:music|audio|lyric|lyrics|visualizer|video))*|lyrics?|audio|music video|visualizer)[\\])]\\s*$", RegexOption.IGNORE_CASE), "").trim().ifBlank { title }
}

internal fun regionalGenres(code: String?): List<HomeCategory> = when (code) {
    "IN" -> listOf(HomeCategory("Bollywood", "From the big screen", "Bollywood songs", Icons.Rounded.Movie, 0xFF956342),
        HomeCategory("Punjabi", "Feel the beat", "Punjabi songs", Icons.Rounded.GraphicEq, 0xFF8A5478))
    "BR" -> listOf(HomeCategory("MPB", "Sons do Brasil", "MPB songs", Icons.Rounded.WbSunny, 0xFF617D42))
    "JP" -> listOf(HomeCategory("J-pop", "Discover Japan", "J-pop songs", Icons.Rounded.AutoAwesome, 0xFF95637F))
    "KR" -> listOf(HomeCategory("K-pop", "Discover Korea", "K-pop songs", Icons.Rounded.AutoAwesome, 0xFF8461AA))
    "MX", "ES", "AR", "CO", "CL" -> listOf(HomeCategory("Latin", "Find your rhythm", "Latin songs", Icons.Rounded.WbSunny, 0xFF997047))
    else -> emptyList()
}
