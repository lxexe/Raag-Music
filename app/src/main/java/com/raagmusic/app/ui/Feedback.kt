@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.raagmusic.app.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

val LocalFeedback = staticCompositionLocalOf<SnackbarHostState?> { null }
internal val LocalExpandSettings = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalSettingsScroll = staticCompositionLocalOf<ScrollState?> { null }
internal val LocalSettingsList = staticCompositionLocalOf<LazyListState?> { null }
internal data class SettingsWindow(val fullScreen: Boolean, val toggle: () -> Unit)
internal val LocalSettingsWindow = staticCompositionLocalOf<SettingsWindow?> { null }

/** Expand on reaching the bottom during a real scroll, including a fling. */
@Composable
internal fun ExpandSettingsAtBottom(atBottom: () -> Boolean) {
    val predicate by rememberUpdatedState(atBottom)
    val expand by rememberUpdatedState(LocalExpandSettings.current)
    LaunchedEffect(Unit) {
        snapshotFlow { predicate() }.distinctUntilChanged().collect { bottom -> if (bottom) expand?.invoke() }
    }
}

/** Sheets share the root host so confirmations and Undo remain above the active surface. */
@Composable
fun RaagSheet(onDismissRequest: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = MaterialTheme.colorScheme.surface, fullHeight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val feedback = LocalFeedback.current
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, containerColor = containerColor,
        tonalElevation = 0.dp, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        ThemeSheetSystemBars()
        Box(Modifier.fillMaxWidth().then(if (fullHeight) Modifier.fillMaxHeight() else Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 70).coerceAtLeast(180).dp))) {
            Column(content = content)
            feedback?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) }
        }
    }
}

/** Native sheet scrolling expands first; an upward pull at the bottom enters fullscreen. */
@Composable
fun ExpandableSettingsSheet(onDismiss: () -> Unit, title: String = "Settings", content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                SettingsHeader(title, onDismiss)
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
