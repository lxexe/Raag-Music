package com.raagmusic.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*
import java.time.LocalDate

@UnstableApi
@Composable
fun ListeningSettings(vm: MusicViewModel, onAppearance: () -> Unit, onCountry: () -> Unit) {
    ThemeSheetSystemBars()
    val features by vm.features.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val region by vm.homeRegion.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf(false) }
    var analyticsTab by remember { mutableStateOf<Int?>(null) }
    var audio by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val scroll = LocalSettingsScroll.current ?: rememberScrollState()
    ExpandSettingsAtBottom { scroll.value > 0 && !scroll.canScrollForward && scroll.isScrollInProgress }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportLibrary) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pendingImport = it }
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsGroup("General") {
            SettingsAction("Profile", features.profile.displayName.ifBlank { "Add your name" }) { editName = true }
            SettingsAction("Music country", region?.displayName() ?: "Automatic", onClick = onCountry)
            SettingsAction("Appearance", "Theme, player and lyrics", onClick = onAppearance)
        }
        SettingsGroup("Playback") {
            SettingsChoice("Video playback", features.preferences.videoMode, VideoPlaybackMode.entries, { it.label }) { value -> vm.updatePreferences { it.copy(videoMode = value) } }
            SettingsChoice("Video quality", features.preferences.videoQuality, VideoQuality.entries, { it.label }) { value -> vm.updatePreferences { it.copy(videoQuality = value) } }
            SettingSwitch("Remember playback", "Resume where you left off", features.preferences.rememberQueue) { value -> vm.updatePreferences { it.copy(rememberQueue = value) } }
            SettingSwitch("Stop music on exit", "Keep playing when switching apps", features.preferences.stopOnExit) { value -> vm.updatePreferences { it.copy(stopOnExit = value) } }
            SettingsChoice("Wi-Fi quality", features.preferences.wifiQuality, AudioQuality.entries, { it.label }) { quality -> vm.updatePreferences { it.copy(wifiQuality = quality) } }
            SettingsChoice("Mobile data quality", features.preferences.mobileQuality, AudioQuality.entries, { it.label }) { quality -> vm.updatePreferences { it.copy(mobileQuality = quality) } }
            SettingsAction("Equalizer & transitions", if (audio) "Hide audio controls" else "Fine-tune your sound") { audio = !audio }
            if (audio) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { AudioSettings(vm, features.preferences) }
        }
        SettingsGroup("Updates") {
            SettingsAction("Check for updates", "Version ${com.raagmusic.app.BuildConfig.VERSION_NAME} · Checks GitHub daily", onClick = vm::checkForUpdates)
        }
        SettingsGroup("Privacy") {
            SettingsAction("Basic usage statistics", "App usage reporting and what is shared") { analyticsTab = 0 }
            SettingsAction("Listening analytics", "Library Insights and history · stays on this device") { analyticsTab = 1 }
        }
        SettingsGroup("Library backup") {
            SettingsAction("Export library", "Favorites and playlists", enabled = !busy) { export.launch("raag-music-library-${LocalDate.now()}.json") }
            SettingsAction("Restore library", "Merge a saved backup", enabled = !busy) { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        SettingsGroup("About") {
            SettingsAction("About Raag Music", "Version ${com.raagmusic.app.BuildConfig.VERSION_NAME} · Open-source licenses") { legal = true }
        }
    }
    if (legal) LegalInformation(onClose = { legal = false })
    analyticsTab?.let { selected -> AnalyticsSettings(vm, selected) { analyticsTab = null } }
    if (editName) ListenerNameDialog(features.profile.name, firstTime = false,
        onDismiss = { editName = false }, onSave = { vm.saveListenerName(it); editName = false })
    pendingImport?.let { uri -> AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("Restore library backup?") },
        text = { Text("Favorites and playlists will merge with your library. Audio files and listening history are not included.") },
        confirmButton = { TextButton(onClick = { vm.importLibrary(uri); pendingImport = null }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }) }
}

@Composable
internal fun SettingsHeader(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
        LocalSettingsWindow.current?.let { window ->
            Glyph(if (window.fullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                if (window.fullScreen) "Exit full-screen settings" else "Full-screen settings", onClick = window.toggle)
        }
        Glyph(Icons.Rounded.Close, "Close ${title.lowercase()}", onClick = onClose)
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = Muted)
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
internal fun SettingsAction(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        MusicIcon(Icons.Rounded.ChevronRight, null, tint = Muted, modifier = Modifier.padding(start = 12.dp).size(20.dp))
    }
}

@Composable
internal fun <T> SettingsChoice(title: String, value: T, choices: List<T>, label: (T) -> String, onChange: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SettingsAction(title, label(value), onClick = { open = true })
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        Column {
            choices.forEach { choice ->
                Row(Modifier.fillMaxWidth().clickable { onChange(choice); open = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(value == choice, null)
                    Text(label(choice), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
}

@Composable
internal fun SettingSwitch(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
}
