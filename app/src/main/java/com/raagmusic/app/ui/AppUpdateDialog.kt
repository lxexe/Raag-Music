package com.raagmusic.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.raagmusic.app.BuildConfig
import com.raagmusic.app.updates.UpdateState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AppUpdateDialog(state: UpdateState, onCheck: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val release = state.release
    val newer = release?.isNewerThan(BuildConfig.VERSION_NAME) == true
    var browserError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (newer) "New version available" else "App updates") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Installed version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                if (state.checking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Checking GitHub…")
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!state.checking && state.error == null && state.lastChecked > 0) {
                    Text(if (newer) "Raag Music ${release!!.version} is available." else "You're up to date.",
                        style = MaterialTheme.typography.titleMedium)
                }
                if (state.lastChecked > 0) Text(
                    "Last checked ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.lastChecked))}",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                if (release != null) {
                    HorizontalDivider()
                    Text("What's new in ${release.version}", style = MaterialTheme.typography.titleMedium)
                    // Keep notes selectable, safe and available offline after a successful check.
                    SelectionContainer {
                        Text(release.notes.ifBlank { "No release notes were provided for this version." },
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (browserError) Text("Couldn't open a browser. Please try again.", color = MaterialTheme.colorScheme.error)
                Text("Checks about once a day when online. Android chooses the background time. No music or library data is sent. Downloads open in your browser; you choose whether to install.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (newer && release != null) {
                TextButton(onClick = {
                    val url = release.apkUrl ?: release.pageUrl
                    browserError = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
                }) { Text(if (release.apkUrl != null) "Download update" else "View release") }
            } else TextButton(onClick = onCheck, enabled = !state.checking) { Text("Check again") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (newer) "Later" else "Close") } },
    )
}
