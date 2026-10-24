package com.raagmusic.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.Track
import com.raagmusic.app.playback.AudioVisualization

@Composable
fun AudioWaveform(playing: Boolean, modifier: Modifier = Modifier) {
    val levels by AudioVisualization.levels.collectAsStateWithLifecycle()
    val color = Lavender
    val enabled = LocalAppearance.current.effects && !LocalAppearance.current.reduceMotion && playing
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(enabled, lifecycle) {
        var registered = false
        fun update() {
            val wanted = enabled && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (wanted != registered) { registered = wanted; if (wanted) AudioVisualization.consumers.incrementAndGet() else AudioVisualization.consumers.decrementAndGet() }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); if (registered) AudioVisualization.consumers.decrementAndGet() }
    }
    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        val step = size.width / levels.size
        levels.forEachIndexed { index, amplitude ->
            val height = if (enabled) (amplitude * size.height).coerceAtLeast(2.dp.toPx()) else 2.dp.toPx()
            drawLine(color.copy(alpha = if (enabled) .8f else .25f), Offset((index + .5f) * step, (size.height - height) / 2),
                Offset((index + .5f) * step, (size.height + height) / 2), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

@UnstableApi
@Composable
fun AmbientPlayer(vm: MusicViewModel, track: Track, onClose: () -> Unit) {
    val state by vm.playback.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().panelHeaderGestures(onClose).background(Ink).dynamicPlayerBackground(track.artwork).navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Glyph(Icons.Rounded.Close, "Exit ambient mode", onClick = onClose) }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Artwork(track, Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f), highQuality = true)
            Text(track.title, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(track.artist, color = Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            AudioWaveform(state.playing)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(PlayerIcons.Previous, "Previous", onClick = vm.player::previous)
            Glyph(if (state.playWhenReady) PlayerIcons.Pause else PlayerIcons.Play, if (state.playWhenReady) "Pause" else "Play", Lavender, vm.player::toggle)
            Glyph(PlayerIcons.Next, "Next", onClick = vm.player::next)
        }
    }
}
