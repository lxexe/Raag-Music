@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.raagmusic.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.raagmusic.app.domain.MotionLevel

val LocalPlayerTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalPlayerVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun motionDuration(normal: Int = 220): Int = when {
    LocalAppearance.current.reduceMotion -> 0
    LocalAppearance.current.motion == MotionLevel.SUBTLE -> (normal * .8f).toInt()
    else -> normal
}

@Composable
fun Modifier.pressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed && !LocalAppearance.current.reduceMotion) .92f else 1f,
        spring(dampingRatio = .75f, stiffness = Spring.StiffnessMedium), label = "Press feedback")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable
fun hapticAction(action: () -> Unit): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val enabled = LocalAppearance.current.haptics
    return { if (enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); action() }
}

@Composable
fun Modifier.playerArtwork(id: String): Modifier {
    val scope = LocalPlayerTransition.current ?: return this
    val visibility = LocalPlayerVisibility.current ?: return this
    if (LocalAppearance.current.reduceMotion) return this
    return with(scope) { this@playerArtwork.sharedElement(rememberSharedContentState("player-cover-$id"), visibility,
        boundsTransform = { _, _ -> tween(360, easing = FastOutSlowInEasing) }) }
}

@Composable
fun Modifier.playerTitle(id: String): Modifier {
    val scope = LocalPlayerTransition.current ?: return this
    val visibility = LocalPlayerVisibility.current ?: return this
    if (LocalAppearance.current.reduceMotion) return this
    return with(scope) { this@playerTitle.sharedBounds(rememberSharedContentState("player-title-$id"), visibility,
        boundsTransform = { _, _ -> tween(360, easing = FastOutSlowInEasing) }) }
}

/** A playback-state indicator, not an audio-amplitude visualizer. Animation exists only while playing. */
@Composable
fun PlayingIndicator(playing: Boolean, modifier: Modifier = Modifier) {
    val accent = Lavender
    val moving = playing && !LocalAppearance.current.reduceMotion
    val phase = if (moving) rememberInfiniteTransition(label = "Playing indicator").animateFloat(0f, 6.283f,
        infiniteRepeatable(tween(900, easing = LinearEasing)), label = "Playing bars") else null
    Canvas(modifier.size(18.dp, 20.dp)) {
        repeat(3) { i ->
            val height = size.height * if (phase != null) (.3f + .65f * kotlin.math.abs(kotlin.math.sin(phase.value + i * 1.3f))) else .35f
            drawRoundRect(accent, androidx.compose.ui.geometry.Offset(i * size.width / 3, size.height - height),
                androidx.compose.ui.geometry.Size(size.width / 5, height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}
