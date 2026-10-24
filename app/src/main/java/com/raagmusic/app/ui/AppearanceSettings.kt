package com.raagmusic.app.ui

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*

@UnstableApi
@Composable
fun AppearanceSettings(vm: MusicViewModel) {
    ThemeSheetSystemBars()
    val savedTheme by vm.theme.collectAsStateWithLifecycle()
    val theme = savedTheme ?: LocalAppearance.current
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = list,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            SettingsGroup("Theme") {
                SettingsChoice("App theme", theme.mode.takeIf { it in listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.AMOLED) } ?: ThemeMode.AMOLED,
                    listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.AMOLED), { it.label }) { mode -> vm.updateTheme { it.copy(mode = mode) } }
            }
        }
        item {
            SettingsGroup("Player & lyrics") {
                SettingsChoice("Playing style", theme.playerStyle.takeIf { it == PlayerStyle.APPLE } ?: PlayerStyle.COMPACT,
                    listOf(PlayerStyle.COMPACT, PlayerStyle.APPLE), { it.label }) { style -> vm.updateTheme { it.copy(playerStyle = style) } }
                SettingsChoice("Lyric style", theme.lyricStyle, LyricStyle.entries, { it.label }) { style -> vm.updateTheme { it.copy(lyricStyle = style) } }
                Text("The player follows the artwork colors with a bright, softly moving background.", color = Muted,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
        item {
            SettingsGroup("Comfort") {
                SettingSwitch("Reduce motion", "Use a still artwork background", theme.reduceMotion) { value -> vm.updateTheme { it.copy(reduceMotion = value) } }
                SettingSwitch("Haptic feedback", "A subtle response to touch", theme.haptics) { value -> vm.updateTheme { it.copy(haptics = value) } }
            }
        }
    }
}
