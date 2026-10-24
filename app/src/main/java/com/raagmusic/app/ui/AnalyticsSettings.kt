package com.raagmusic.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.RaagApplication

@UnstableApi
@Composable
internal fun AnalyticsSettings(vm: MusicViewModel, initialTab: Int, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val reporting = (LocalContext.current.applicationContext as RaagApplication).usageReporting
    val enabled by reporting.enabled.collectAsStateWithLifecycle()
    val features by vm.features.collectAsStateWithLifecycle()
    var clearDialog by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                SettingsHeader("Privacy & analytics", onClose)
                TabRow(selectedTabIndex = tab) {
                    listOf("App usage", "Listening").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                key(tab) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        if (tab == 0) {
                            PrivacyExplanation("Basic usage statistics", "Helps us understand how often Raag Music is used and which app versions are active.")
                            if (reporting.available) {
                                SettingsGroup("Reporting") {
                                    SettingSwitch("Share basic usage statistics", "Enabled by default. You can turn it off at any time.", enabled, reporting::setEnabled)
                                }
                                PrivacyExplanation("What is shared", "First launches, sessions, engagement, app updates and app version. Firebase also processes an installation identifier, Android version, device model, language and approximate region.")
                            } else {
                                PrivacyExplanation("No reporting in this build", "This build contains no Firebase SDK and sends no basic usage statistics.")
                            }
                            PrivacyExplanation("Your music and personal content stay private", "Your name, songs, artists, searches, playlists, library contents and listening history are never sent to analytics.")
                            PrivacyExplanation("You stay in control", "Basic usage reporting is separate from Listening analytics. Advertising identifiers, ad personalization and automatic screen tracking are disabled. Turning reporting off stops further collection and resets local Firebase analytics data; it does not remove data already uploaded.")
                        } else {
                            PrivacyExplanation("Listening analytics", "Powers your Library Insights and helps personalize recommendations. These records stay on this device and are not sent to Firebase.")
                            SettingsGroup("On this device") {
                                SettingSwitch("Listening analytics", "Save song plays, listening time and skips locally.", features.preferences.analyticsEnabled) { value ->
                                    vm.updatePreferences { it.copy(analyticsEnabled = value) }
                                }
                                SettingSwitch("Recently played history", "Keep your recently played songs on this device.", features.preferences.collectHistory) { value ->
                                    vm.updatePreferences { it.copy(collectHistory = value) }
                                }
                            }
                            PrivacyExplanation("Separate controls", "Turning off Listening analytics stops new listening statistics. It does not change basic app usage reporting or delete existing records. You can remove local records below.")
                            SettingsGroup("Manage local history") {
                                SettingsAction("Clear listening history", "Remove local statistics and recently played records") { clearDialog = true }
                                if (features.lessLikeIds.isNotEmpty()) SettingsAction("Reset mix feedback", "${features.lessLikeIds.size} songs", onClick = vm::resetFeedback)
                            }
                        }
                    }
                }
            }
        }
        if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Clear listening history?") },
            text = { Text("Remove listening statistics and recently played songs on this device. Favorites, playlists and the app usage reporting setting stay unchanged.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); clearDialog = false }) { Text("Clear history") } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } })
    }
}

@Composable
private fun PrivacyExplanation(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
