package com.raagmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*

@UnstableApi
@Composable
fun ActiveSleepBadge(vm: MusicViewModel) {
    val features by vm.features.collectAsStateWithLifecycle()
    if (features.sleepTimer.active) {
        val state by vm.playback.collectAsStateWithLifecycle()
        AssistChip(onClick = { vm.sleep(0) }, label = { Text(if (features.sleepTimer.finishTrackId != null) "Sleep after song · Cancel" else "Sleep in ${sleepCountdownLabel(state.sleepRemainingMs)} · Cancel") },
            leadingIcon = { MusicIcon(Icons.Rounded.Bedtime, null, Modifier.size(16.dp)) })
    }
}

@UnstableApi
@Composable
fun PlayerSleepTimer(vm: MusicViewModel) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    var timerDialog by remember { mutableStateOf(false) }
    val sleep = features.sleepTimer
    val remainingTimer = if (state.sleepDeadlineEpochMs == sleep.deadlineEpochMs) state.sleepRemainingMs
        else (sleep.deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(0)
    val timerLabel = when {
        sleep.finishTrackId != null -> "End of song${if (state.sleepEndOfTrack && state.sleepRemainingMs > 0) " · ${sleepCountdownLabel(state.sleepRemainingMs)}" else ""}"
        sleep.active -> "${sleepCountdownLabel(remainingTimer)} left"
        else -> "Sleep timer"
    }
    TextButton(onClick = { timerDialog = true }, modifier = Modifier.fillMaxWidth()) {
        MusicIcon(Icons.Rounded.Bedtime, null, tint = Lavender)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sleep timer", color = ContentColor)
            Text(if (sleep.active) timerLabel else "Off", color = Muted, fontSize = 12.sp)
        }
    }
    if(timerDialog) AlertDialog(onDismissRequest = { timerDialog = false }, title = { Text("Sleep timer") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (sleep.active) Text(timerLabel, color = Lavender, fontWeight = FontWeight.SemiBold)
            Text("Music pauses when the timer ends, even if you leave the app.", color = Muted, fontSize = 12.sp)
            listOf(5, 15, 30, 45, 60).forEach { minutes -> TextButton(onClick = { vm.sleep(minutes); timerDialog = false }) { Text("$minutes minutes") } }
            TextButton(onClick = { vm.sleepAtEndOfTrack(); timerDialog = false }) { Text("At the end of this song") }
            if (sleep.active) TextButton(onClick = { vm.sleep(0); timerDialog = false }) { Text("Cancel timer") }
        }
    }, confirmButton = { TextButton(onClick = { timerDialog = false }) { Text("Done") } })
}
