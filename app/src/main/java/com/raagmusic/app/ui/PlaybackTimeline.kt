package com.raagmusic.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Played, fetched and pending audio remain visible in one line, including during seeks. */
@Composable
fun PlaybackTimeline(
    position: Long, duration: Long, bufferedPosition: Long, loading: Boolean,
    modifier: Modifier = Modifier, interactive: Boolean = false,
    onPreview: (Long?) -> Unit = {}, onSeek: (Long) -> Unit = {},
) {
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0) (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val pulse = if (loading && !LocalAppearance.current.reduceMotion) {
        val transition = rememberInfiniteTransition(label = "Audio loading")
        val alpha = transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "Timeline pulse")
        alpha
    } else null
    var drag by remember { mutableStateOf<Float?>(null) }
    val latestDuration by rememberUpdatedState(duration)
    val preview by rememberUpdatedState(onPreview)
    val seek by rememberUpdatedState(onSeek)
    var controls: Modifier = Modifier
    if (interactive && duration > 0) {
        controls = controls.semantics {
            contentDescription = "Song timeline"
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            if (loading) stateDescription = "Loading audio"
            setProgress { value -> seek((value.coerceIn(0f, 1f) * latestDuration).toLong()); true }
        }.pointerInput(Unit) {
            detectTapGestures { point -> seek(((point.x / size.width).coerceIn(0f, 1f) * latestDuration).toLong()) }
        }.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f); preview((drag!! * latestDuration).toLong()) },
                onDragEnd = { drag?.let { seek((it * latestDuration).toLong()) }; drag = null; preview(null) },
                onDragCancel = { drag = null; preview(null) },
            ) { change, amount ->
                change.consume()
                // Move farther below the timeline for finer adjustments without losing the current position.
                val precision = if (change.position.y > size.height * 2) .15f else if (change.position.y > size.height) .4f else 1f
                drag = ((drag ?: fraction) + amount / size.width * precision).coerceIn(0f, 1f)
                preview((drag!! * latestDuration).toLong())
            }
        }
    }
    val accent = Lavender
    val secondary = Mint
    val content = ContentColor
    Canvas(modifier.fillMaxWidth().height(if (interactive) 48.dp else 3.dp).then(controls)) {
        val y = size.height / 2
        val thickness = (if (interactive) 4.dp else 2.dp).toPx()
        fun line(end: Float, color: Color) = drawLine(color, Offset(0f, y), Offset(size.width * end, y), thickness, StrokeCap.Round)
        line(1f, content.copy(alpha = 0.08f))
        line(buffered, content.copy(alpha = 0.32f))
        val played = drag ?: fraction
        line(played, accent)
        if (loading) {
            // A pulse at the requested timestamp replaces shifting text beneath the artist.
            val start = (played - 0.025f).coerceAtLeast(0f) * size.width
            val end = (played + 0.10f).coerceAtMost(1f) * size.width
            drawLine(secondary.copy(alpha = pulse?.value ?: 1f), Offset(start, y), Offset(end, y), thickness + 1.dp.toPx(), StrokeCap.Round)
        }
        if (interactive) drawCircle(accent, (if (drag != null) 7.dp else 5.dp).toPx(), Offset(size.width * played, y))
    }
}
