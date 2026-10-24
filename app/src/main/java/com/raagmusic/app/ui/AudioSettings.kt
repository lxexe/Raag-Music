package com.raagmusic.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*

@UnstableApi
@Composable
fun AudioSettings(vm: MusicViewModel, preferences: ListeningPreferences) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    Text("Track transitions", style = MaterialTheme.typography.titleLarge)
    TransitionMode.entries.forEach { mode ->
        Row(Modifier.fillMaxWidth().clickable { vm.updatePreferences { it.copy(transitionMode = mode) } }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(preferences.transitionMode == mode, { vm.updatePreferences { it.copy(transitionMode = mode) } }); Text(mode.label)
        }
    }
    if (preferences.transitionMode == TransitionMode.CROSSFADE) {
        Text("Crossfade · ${preferences.crossfadeSeconds} seconds")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3, 5, 8, 10, 12).forEach { seconds ->
                Pill("${seconds}s", preferences.crossfadeSeconds == seconds) {
                    vm.updatePreferences { it.copy(crossfadeSeconds = seconds) }
                }
            }
        }
        CrossfadePreview(preferences.crossfadeSeconds)
        Text("Overlaps the next song when ready. Albums in order keep seamless playback. Manual skips use normal playback; short tracks use a shorter fade.", color = Muted)
    } else Text("Play the queue continuously. Seamless transitions depend on the recording, format and available buffering.", color = Muted)
    HorizontalDivider()
    Text("Equalizer", style = MaterialTheme.typography.titleLarge)
    val settings = preferences.equalizer
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Enable audio effects", modifier = Modifier.weight(1f))
        Switch(settings.enabled, { enabled -> vm.updatePreferences { it.copy(equalizer = it.equalizer.copy(enabled = enabled)) } })
    }
    Text(playback.eqStatus ?: "Start playback to check equalizer availability on this device.", color = Muted)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EqualizerPreset.entries.forEach { preset -> Pill(preset.label, settings.preset == preset) {
            vm.updatePreferences { it.copy(equalizer = it.equalizer.copy(preset = preset)) }
        } }
    }
    EqualizerPreview(settings.gains, settings.enabled)
    if (settings.preset == EqualizerPreset.CUSTOM) {
        listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz").forEachIndexed { index, frequency ->
            var gain by remember(settings.customGains, index) { mutableFloatStateOf(settings.gains[index].toFloat()) }
            Text("$frequency · ${gain.roundToInt()} dB")
            Slider(gain, { gain = it }, valueRange = -12f..12f, steps = 23,
                onValueChangeFinished = { vm.updatePreferences { p ->
                    val gains = p.equalizer.gains.toMutableList().apply { set(index, gain.roundToInt()) }
                    p.copy(equalizer = p.equalizer.copy(customGains = gains))
                } })
        }
    }
    Text("Presets are remembered. Frequency bands and effect support depend on the device and audio output.", color = Muted)
}

@Composable
private fun CrossfadePreview(seconds: Int) {
    val accent = Lavender
    val next = Mint
    Column(Modifier.fillMaxWidth().glass().padding(14.dp)) {
        Text("Transition preview · $seconds seconds", style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(70.dp).semantics { contentDescription = "Current song fades out while the next song fades in over $seconds seconds" }) {
            drawLine(accent, Offset(0f, 8.dp.toPx()), Offset(size.width, size.height - 8.dp.toPx()), 3.dp.toPx())
            drawLine(next, Offset(0f, size.height - 8.dp.toPx()), Offset(size.width, 8.dp.toPx()), 3.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Current song", color = accent); Text("Next song", color = next)
        }
    }
}

@Composable
private fun EqualizerPreview(gains: List<Int>, enabled: Boolean) {
    val accent = Lavender
    val muted = Muted
    val animated = gains.mapIndexed { index, gain -> androidx.compose.animation.core.animateFloatAsState(
        if (enabled) gain.toFloat() else 0f, androidx.compose.animation.core.tween(motionDuration(200)), label = "EQ band $index") }
    Column(Modifier.fillMaxWidth().glass().padding(14.dp)) {
        Text(if (enabled) "Equalizer curve" else "Equalizer bypassed", style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(110.dp).semantics {
            contentDescription = if (enabled) "Equalizer gains: ${gains.joinToString()} decibels from bass to treble" else "Equalizer off, flat response"
        }) {
            drawLine(muted.copy(alpha = .3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            val path = Path()
            animated.forEachIndexed { i, gain ->
                val x = i * size.width / (animated.size - 1).coerceAtLeast(1)
                val y = size.height * (.5f - gain.value / 30f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(accent, 4.dp.toPx(), Offset(x, y))
            }
            drawPath(path, accent, style = Stroke(3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Bass", color = Muted); Text("Treble", color = Muted) }
    }
}
