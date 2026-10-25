package com.raagmusic.app.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.raagmusic.app.domain.Source
import com.raagmusic.app.domain.Track
import com.raagmusic.app.playback.PlaybackFailure
import com.raagmusic.app.playback.PlaybackFailureKind
import kotlinx.coroutines.delay

@Composable
internal fun PlaybackFailureCard(failure: PlaybackFailure, track: Track, onRetry: () -> Unit) {
    val context = LocalContext.current
    var now by remember(failure) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(failure) {
        while ((failure.retryAtEpochMillis ?: 0) > now) { delay(1_000); now = System.currentTimeMillis() }
    }
    val seconds = ((failure.retryAtEpochMillis ?: 0) - now + 999).coerceAtLeast(0) / 1000
    var externalError by remember(track.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(failure.title, style = MaterialTheme.typography.titleSmall)
            Text(failure.message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (seconds == 0L) onRetry() }, enabled = seconds == 0L) {
                    Text(if (seconds > 0) "Retry in ${seconds}s" else "Try again")
                }
                if (track.source == Source.YOUTUBE && Regex("[A-Za-z0-9_-]{11}").matches(track.id)) {
                    TextButton(onClick = {
                        externalError = !openYoutubeExternally(context, track.id)
                    }) { Text("Open in YouTube") }
                }
            }
            if (failure.kind == PlaybackFailureKind.CONNECTION) Text("Downloaded music can still be played offline.", style = MaterialTheme.typography.bodySmall)
            if (externalError) Text("No app could open YouTube on this device.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Never route the fallback URL back into Raag's own YouTube link handler. */
internal fun openYoutubeExternally(context: android.content.Context, id: String): Boolean {
    if (!Regex("[A-Za-z0-9_-]{11}").matches(id)) return false
    val intent = Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$id".toUri())
    if (runCatching { context.startActivity(Intent(intent).setPackage("com.google.android.youtube")) }.isSuccess) return true
    val browser = context.packageManager.resolveActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), 0)
        ?.activityInfo?.packageName?.takeIf { it != context.packageName }
        ?: context.packageManager.queryIntentActivities(intent, 0).firstOrNull { it.activityInfo.packageName != context.packageName }?.activityInfo?.packageName
        ?: return false
    return runCatching { context.startActivity(Intent(intent).setPackage(browser)) }.isSuccess
}
