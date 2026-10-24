package com.raagmusic.app.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.util.UnstableApi
import com.raagmusic.app.playback.PlaybackState

/** A surface for the service-owned player. Leaving this view disables the video decoder. */
@UnstableApi
@Composable
internal fun PlayerVideo(vm: MusicViewModel, state: PlaybackState, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    DisposableEffect(surface, lifecycleState) {
        val view = surface
        if (view != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) vm.player.attachVideoSurface(view)
        onDispose { if (view != null) vm.player.detachVideoSurface(view) }
    }
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toFloat() / state.videoHeight else 16f / 9
        val width = minOf(maxWidth, maxHeight * ratio)
        AndroidView(factory = { context -> SurfaceView(context).also { surface = it } },
            modifier = Modifier.width(width).aspectRatio(ratio), update = { it.keepScreenOn = state.playing })
        if (state.buffering) CircularProgressIndicator(color = Color.White)
    }
}
