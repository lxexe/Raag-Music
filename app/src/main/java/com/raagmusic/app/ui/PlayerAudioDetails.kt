package com.raagmusic.app.ui

import android.net.ConnectivityManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.Track
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.raagmusic.app.playback.PlaybackState

private data class AudioDetails(val trackId: String?, val bitrate: Int?, val mimeType: String?, val sampleRate: Int?)
private fun PlaybackState.audioDetails() = AudioDetails(trackId, bitrate, audioMimeType, sampleRate)

@UnstableApi
@Composable
fun PlayerAudioDetails(vm: MusicViewModel, track: Track) {
    val qualityFlow = remember(vm) { vm.playback.map { it.audioDetails() }.distinctUntilChanged() }
    val quality by qualityFlow.collectAsStateWithLifecycle(vm.playback.value.audioDetails())
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val metered = LocalContext.current.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    var details by remember { mutableStateOf(false) }
    val current = quality.takeIf { it.trackId == track.id }
    val bitrate = current?.bitrate?.let { "${it / 1000} kbps" } ?: "Bitrate unavailable"
    val codec = when (current?.mimeType) { "audio/opus" -> "Opus"; "audio/mp4a-latm" -> "AAC"; "audio/vorbis" -> "Vorbis"; else -> null }
    val streamDetails = listOfNotNull(codec, bitrate).joinToString(" · ")
    val policy = if (metered) preferences.mobileQuality else preferences.wifiQuality
    TextButton(onClick = { details = true }, modifier = Modifier.fillMaxWidth()) {
        MusicIcon(Icons.Rounded.HighQuality, null, tint = Lavender)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Audio quality", color = ContentColor)
            Text(streamDetails, color = Muted, fontSize = 12.sp)
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Audio quality") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Current stream · $streamDetails")
            current?.sampleRate?.let { Text("Sample rate · ${it / 1000.0} kHz", color = Muted) }
            Text("${if (metered) "Mobile / metered" else "Wi-Fi / unmetered"} setting · ${policy.label}", color = Muted)
            Text("Change quality in Settings. Changes apply when a song starts again.", color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } })
}
