package com.raagmusic.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import kotlinx.coroutines.flow.combine
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.text.style.TextAlign

@UnstableApi
@Composable
fun LyricsPanel(vm: MusicViewModel, track: Track, fullScreen: Boolean = false, embedded: Boolean = false,
    list: LazyListState = rememberLazyListState(), plainScroll: ScrollState = rememberScrollState(), following: MutableState<Boolean> = rememberSaveable(track.id) { mutableStateOf(true) },
    onToggleFullScreen: () -> Unit = {}, onClose: () -> Unit) {
    val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
    val experienceFlow = remember(vm) { vm.features.map { it.experience }.distinctUntilChanged() }
    val prefs by experienceFlow.collectAsStateWithLifecycle(ExperiencePreferences())
    var options by remember { mutableStateOf(false) }
    val activeLine = remember(vm, track.id) {
        combine(vm.lyrics, vm.playback, vm.features.map { it.experience.lyricOffsetMs }.distinctUntilChanged()) { lyrics, playback, offset ->
            if (lyrics.trackId == track.id && playback.trackId == track.id) lyrics.lyrics?.activeIndex((playback.position + offset).coerceAtLeast(0)) ?: -1 else -1
        }.distinctUntilChanged()
    }
    val active by activeLine.collectAsStateWithLifecycle(-1)
    val artists = rememberSongArtists(vm, track)
    val appearance = LocalAppearance.current
    val apple = appearance.lyricStyle == LyricStyle.APPLE
    val reduceMotion = appearance.reduceMotion
    var follow by following
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(track.id) { vm.loadLyrics(track) }
    val lyrics = lyricsState.lyrics.takeIf { lyricsState.trackId == track.id }
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(active, follow, lyrics) {
        if (active >= 0 && follow) {
            // Center the active verse instead of pushing it against the header.
            val offset = -(list.layoutInfo.viewportSize.height * .32f).toInt()
            if (reduceMotion) list.scrollToItem(active, offset) else list.animateScrollToItem(active, offset)
        }
    }
    val collapseLyrics: () -> Unit = { if (fullScreen) onToggleFullScreen() else onClose() }
    val expandLyrics: (() -> Unit)? = if (fullScreen) null else onToggleFullScreen
    Column(Modifier.fillMaxSize().scrollPanelGestures(collapseLyrics, expandLyrics)
        .then(if (fullScreen) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)
        .padding(horizontal = if (embedded) 12.dp else 24.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().panelHeaderGestures(collapseLyrics, expandLyrics), verticalAlignment = Alignment.CenterVertically) {
            Text(if (embedded) "Lyrics" else track.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!embedded) Glyph(Icons.Rounded.Tune, "Lyrics appearance and timing") { options = true }
            Glyph(if (fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (fullScreen) "Exit full-screen lyrics" else "Full-screen lyrics", onClick = onToggleFullScreen)
            Glyph(Icons.Rounded.Close, "Back to player", onClick = onClose)
        }
        Box(Modifier.weight(1f).fillMaxWidth().expandLyricsOnRead(embedded) { follow = false; onToggleFullScreen() }) {
            when {
                lyricsState.loading -> SongSkeletons(if (embedded) 3 else 5)
                lyricsState.error != null -> SectionFailure(lyricsState.error!!) { vm.loadLyrics(track, retry = true) }
                lyrics?.instrumental == true -> LyricsEmpty("Just the music", "This recording is instrumental.")
                lyrics != null && lyrics.lines.isNotEmpty() -> {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(0f to Color.Transparent, .05f to Color.Black,
                                .95f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        }, verticalArrangement = Arrangement.spacedBy(if (apple) 18.dp else 8.dp),
                        contentPadding = PaddingValues(top = 22.dp, bottom = if (embedded) 90.dp else 160.dp)) {
                        itemsIndexed(lyrics.lines, key = { i, line -> "$i-${line.timeMs}" }) { i, line ->
                            AnimatedLyricLine(line.text, i == active, i < active,
                                (apple || prefs.lyricBlur) && active >= 0 && kotlin.math.abs(i - active) <= 2 && i != active,
                                reduceMotion, if (apple) if (embedded) 26 else 34 else prefs.lyricTextSize,
                                if (apple) false else prefs.lyricCentered, apple) {
                                follow = true; vm.player.seek((line.timeMs - prefs.lyricOffsetMs).coerceAtLeast(0))
                            }
                        }
                    }
                    if (!follow) TextButton(onClick = { follow = true }, modifier = Modifier.align(Alignment.BottomEnd)) {
                        MusicIcon(Icons.Rounded.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Follow song")
                    }
                }
                lyrics != null && lyrics.plain.isNotBlank() -> {
                    Text(lyrics.plain, fontSize = (if (apple) if (embedded) 26 else 34 else prefs.lyricTextSize).sp,
                        lineHeight = (if (apple) if (embedded) 36f else 46f else prefs.lyricTextSize * 1.5f).sp,
                        fontWeight = if (apple) FontWeight.ExtraBold else FontWeight.SemiBold,
                        modifier = Modifier.fillMaxSize().verticalScroll(plainScroll).padding(vertical = 20.dp))
                }
                else -> LyricsEmpty("Words taking a break", "Lyrics aren't available for this recording yet.")
            }
        }
        lyrics?.provider?.takeIf { it.isNotBlank() }?.let { Text("Lyrics · $it", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        if (fullScreen) LyricsPlaybackControls(vm)
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("Your lyrics") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var textSize by remember(prefs.lyricTextSize) { mutableFloatStateOf(prefs.lyricTextSize.toFloat()) }
            Text("Text size · ${textSize.toInt()}")
            Slider(textSize, { textSize = it }, valueRange = 16f..34f, steps = 17, onValueChangeFinished = { vm.updateExperience { it.copy(lyricTextSize = textSize.toInt()) } })
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Blur adjacent lines", Modifier.weight(1f)); Switch(prefs.lyricBlur, { enabled -> vm.updateExperience { it.copy(lyricBlur = enabled) } }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Center text", Modifier.weight(1f)); Switch(prefs.lyricCentered, { enabled -> vm.updateExperience { it.copy(lyricCentered = enabled) } }) }
            var offset by remember(prefs.lyricOffsetMs) { mutableFloatStateOf(prefs.lyricOffsetMs.toFloat() / 1000) }
            Text("Timing · ${"%.1f".format(offset)} seconds")
            Text("Positive values show lyrics earlier. Negative values show them later.", color = Muted, fontSize = 12.sp)
            Slider(offset, { offset = it }, valueRange = -10f..10f, steps = 39, onValueChangeFinished = { vm.updateExperience { it.copy(lyricOffsetMs = (offset * 1000).toLong()) } })
            TextButton(onClick = { vm.updateExperience { it.copy(lyricOffsetMs = 0) } }) { Text("Reset timing") }
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text("Done") } })
}

/** Observe reading drags without consuming them or interrupting the scroll mid-gesture. */
@Composable
private fun Modifier.expandLyricsOnRead(enabled: Boolean, expand: () -> Unit): Modifier {
    val onRead by rememberUpdatedState(expand)
    return if (!enabled) this else pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var distance = 0f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                distance += change.position.y - change.previousPosition.y
            } while (event.changes.any { it.id == down.id && it.pressed })
            if (distance < -32.dp.toPx()) onRead()
        }
    }
}

@UnstableApi
@Composable
private fun LyricsPlaybackControls(vm: MusicViewModel) {
    val state by vm.playback.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().glass().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(timeLabel(state.position), color = Muted, fontSize = 12.sp)
        Glyph(Icons.Rounded.SkipPrevious, "Previous", onClick = vm.player::previous)
        Glyph(if (state.playWhenReady) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playWhenReady) "Pause" else "Play", Lavender, vm.player::toggle)
        Glyph(Icons.Rounded.SkipNext, "Next", onClick = vm.player::next)
        Text(timeLabel(state.duration), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun AnimatedLyricLine(text: String, active: Boolean, past: Boolean, adjacent: Boolean, reduceMotion: Boolean, textSize: Int, centered: Boolean, apple: Boolean = false, onClick: () -> Unit) {
    val duration = if (reduceMotion) 0 else 380
    val color by animateColorAsState(if (active) Color.White else if (past) Color.White.copy(alpha = .35f) else Color.White.copy(alpha = .5f),
        tween(duration), label = "Verse color")
    val blurRadius by animateFloatAsState(if (adjacent) 1.5f else 0f, tween(duration), label = "Verse blur")
    val emphasis by animateFloatAsState(if (active) 1f else 0f, tween(duration, easing = FastOutSlowInEasing), label = "Verse emphasis")
    Row(Modifier.fillMaxWidth().background(Lavender.copy(alpha = if (apple) 0f else emphasis * .08f), RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!apple) Box(Modifier.width(3.dp).height(28.dp).background(Lavender.copy(alpha = emphasis), RoundedCornerShape(2.dp)))
        Text(text.ifBlank { "♪" }, fontSize = textSize.sp, lineHeight = (textSize * 1.4f).sp, textAlign = if (centered) TextAlign.Center else TextAlign.Start, fontWeight = if (apple) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = color, modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp).blur(blurRadius.dp))
    }
}

@Composable
private fun LyricsEmpty(title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 50.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MusicIcon(Icons.Rounded.Lyrics, null, tint = Lavender, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(message, color = Muted)
    }
}
