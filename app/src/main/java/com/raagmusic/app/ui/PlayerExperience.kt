@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.raagmusic.app.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun PlayerPresentation(vm: MusicViewModel, visible: Boolean, track: Track?, onClose: () -> Unit,
    onQueue: () -> Unit, onDownload: () -> Unit, onMore: (Track) -> Unit, onArtist: (String) -> Unit) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val height = LocalConfiguration.current.screenHeightDp.toFloat()
    val reduceMotion = LocalAppearance.current.reduceMotion
    PredictiveBackHandler(visible) { events ->
        try { events.collect { if (!reduceMotion) offset.snapTo(it.progress * height * .5f) }; onClose() }
        catch (_: CancellationException) { scope.launch { offset.animateTo(0f, spring()) } }
    }
    LaunchedEffect(visible) { if (visible) offset.snapTo(0f) }
    AnimatedVisibility(visible && track != null, enter = fadeIn(tween(if (reduceMotion) 0 else 180)) + slideInVertically(tween(if (reduceMotion) 0 else 360)) { it / 4 },
        exit = fadeOut(tween(if (reduceMotion) 0 else 180)) + slideOutVertically(tween(if (reduceMotion) 0 else 300)) { it / 4 }) {
        CompositionLocalProvider(LocalPlayerVisibility provides this) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = offset.value * density }.background(Ink)) {
                track?.let { NowPlayingPanel(vm, it, onQueue, onDownload, onClose, onMore, onArtist) }
            }
        }
    }
}

@Composable
fun VinylArtwork(track: Track, playing: Boolean, modifier: Modifier = Modifier) {
    val moving = playing && LocalAppearance.current.effects && !LocalAppearance.current.reduceMotion
    val rotation = if (moving) rememberInfiniteTransition(label = "Vinyl").animateFloat(0f, 360f,
        infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "Record rotation") else null
    val colors = listOf(Color(0xFF292930), Color(0xFF09090C), Color(0xFF303039))
    Box(modifier.graphicsLayer { rotationZ = rotation?.value ?: 0f }.background(Brush.sweepGradient(colors), CircleShape), contentAlignment = Alignment.Center) {
        repeat(5) { ring -> Box(Modifier.fillMaxSize(.92f - ring * .1f).border(1.dp, Color.White.copy(alpha = .07f), CircleShape)) }
        Artwork(track, Modifier.fillMaxSize(.45f).playerArtwork(track.id).graphicsLayer { shape = CircleShape; clip = true })
        Box(Modifier.size(9.dp).background(Ink, CircleShape))
    }
}

@Composable
fun AudioOutputButton(tint: Color = ContentColor) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val router = remember(context) { context.getSystemService(android.content.Context.MEDIA_ROUTER_SERVICE) as android.media.MediaRouter }
    var open by remember { mutableStateOf(false) }
    Glyph(PlayerIcons.Output, "Choose audio output", tint) { open = true }
    if (open) {
        fun availableRoutes() = (0 until router.routeCount).map(router::getRouteAt)
            .filter { (it.supportedTypes and android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO) != 0 }
        var routes by remember(router) { mutableStateOf(availableRoutes()) }
        var selected by remember(router) { mutableStateOf(router.getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO)) }
        DisposableEffect(router) {
            fun refresh() { routes = availableRoutes(); selected = router.getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO) }
            val callback = object : android.media.MediaRouter.SimpleCallback() {
                override fun onRouteAdded(router: android.media.MediaRouter, route: android.media.MediaRouter.RouteInfo) = refresh()
                override fun onRouteRemoved(router: android.media.MediaRouter, route: android.media.MediaRouter.RouteInfo) = refresh()
                override fun onRouteChanged(router: android.media.MediaRouter, route: android.media.MediaRouter.RouteInfo) = refresh()
                override fun onRouteSelected(router: android.media.MediaRouter, type: Int, route: android.media.MediaRouter.RouteInfo) = refresh()
            }
            router.addCallback(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback, android.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            onDispose { router.removeCallback(callback) }
        }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Audio output") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Available Android audio routes", color = Muted)
                routes.forEach { route ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(route == selected, enabled = route.isEnabled,
                            role = androidx.compose.ui.semantics.Role.RadioButton) {
                            router.selectRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_AUDIO, route); open = false
                        }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(route == selected, null, enabled = route.isEnabled)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(route.name.toString()); route.description?.let { Text(it.toString(), color = Muted) } }
                    }
                }
                TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Connect a Bluetooth device") }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
    }
}

@UnstableApi
@Composable
fun WidePlayerPane(vm: MusicViewModel, track: Track, modifier: Modifier, onExpand: () -> Unit, onMore: () -> Unit) {
    Column(modifier.background(Glass.copy(alpha = .6f)).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = Lavender)
        Artwork(track, Modifier.fillMaxWidth().aspectRatio(1f).playerArtwork(track.id).songSwipeGestures(track.id, vm.player::previousTrack, vm.player::next).clickable(onClickLabel = "Expand player", onClick = onExpand))
        Text(track.title, modifier = Modifier.playerTitle(track.id), style = MaterialTheme.typography.titleLarge, maxLines = 3)
        Text(track.artist, color = Muted, maxLines = 2)
        WidePlayerTransport(vm, track)
        Row {
            AudioOutputButton()
            Glyph(Icons.Rounded.OpenInFull, "Expand player", onClick = onExpand)
            Glyph(PlayerIcons.More, "Song options", onClick = onMore)
        }
        ActiveSleepBadge(vm)
    }
}

@UnstableApi
@Composable
private fun WidePlayerTransport(vm: MusicViewModel, track: Track) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val duration = state.duration.takeIf { it > 0 } ?: track.durationSeconds * 1000
    state.failure?.let { PlaybackFailureCard(it, track, vm.player::toggle) }
    PlaybackTimeline(state.position, duration, state.bufferedPosition, state.buffering,
        interactive = true, onSeek = vm.player::seek)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(timeLabel(state.position), color = Muted); Text(timeLabel(duration), color = Muted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(PlayerIcons.Previous, "Previous", ContentColor, vm.player::previous)
        val wantsPlay = state.playWhenReady && state.error == null
        FilledIconButton(onClick = hapticAction(vm.player::toggle), modifier = Modifier.size(60.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF242424), contentColor = androidx.compose.ui.graphics.Color.White)) {
            MusicIcon(if (wantsPlay) PlayerIcons.Pause else PlayerIcons.Play, if (wantsPlay) "Pause" else "Play")
        }
        Glyph(PlayerIcons.Next, "Next", ContentColor, vm.player::next)
    }
}
