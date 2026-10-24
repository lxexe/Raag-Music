package com.raagmusic.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Keep song navigation on the artwork, away from timeline and list gestures. */
@Composable
fun Modifier.songSwipeGestures(trackId: String, previous: () -> Unit, next: () -> Unit): Modifier {
    val onPrevious by rememberUpdatedState(previous)
    val onNext by rememberUpdatedState(next)
    return pointerInput(trackId) {
        var distance = 0f
        detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f }, onDragEnd = {
            if (distance < -64.dp.toPx()) onNext()
            else if (distance > 64.dp.toPx()) onPrevious()
            distance = 0f
        }) { change, amount -> change.consume(); distance += amount }
    }
}

/** Only unconsumed edge scrolling moves the panel; its content always gets to scroll first. */
@Composable
fun Modifier.scrollPanelGestures(onCollapse: () -> Unit, onExpand: (() -> Unit)? = null): Modifier {
    val collapse by rememberUpdatedState(onCollapse)
    val expand by rememberUpdatedState(onExpand)
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    val reducedMotion = LocalAppearance.current.reduceMotion
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    // Drag accumulation isn't Compose state: scroll events don't recompose the panel.
    val drag = remember { floatArrayOf(0f) }
    val connection = remember(threshold, reducedMotion) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || drag[0] == 0f || drag[0] * available.y >= 0f) return Offset.Zero
                val consumed = if (drag[0] > 0) available.y.coerceAtLeast(-drag[0]) else available.y.coerceAtMost(-drag[0])
                drag[0] += consumed
                scope.launch { offset.snapTo(if (reducedMotion) 0f else drag[0].coerceAtLeast(0f) * .55f) }
                return Offset(0f, consumed)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || (available.y <= 0f && expand == null)) return Offset.Zero
                drag[0] = (drag[0] + available.y).coerceIn(-threshold * 2f, threshold * 2f)
                scope.launch { offset.snapTo(if (reducedMotion) 0f else drag[0].coerceAtLeast(0f) * .55f) }
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val distance = drag[0]
                if (distance == 0f) return Velocity.Zero
                drag[0] = 0f
                if (distance >= threshold || (distance > threshold * .3f && available.y > 1200f)) collapse()
                else if (distance <= -threshold) expand?.invoke()
                offset.animateTo(0f, spring(stiffness = 500f))
                return available
            }
        }
    }
    return nestedScroll(connection).graphicsLayer { translationY = offset.value }
}

/** The header is also a gesture target when there is no scrollable child beneath the finger. */
@Composable
fun Modifier.panelHeaderGestures(onCollapse: () -> Unit, onExpand: (() -> Unit)? = null): Modifier {
    val collapse by rememberUpdatedState(onCollapse)
    val expand by rememberUpdatedState(onExpand)
    return pointerInput(Unit) {
        var distance = 0f
        detectVerticalDragGestures(onDragStart = { distance = 0f }, onDragCancel = { distance = 0f }, onDragEnd = {
            if (distance > 64.dp.toPx()) collapse()
            else if (distance < -64.dp.toPx()) expand?.invoke()
        }) { change, amount -> change.consume(); distance += amount }
    }
}
