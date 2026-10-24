package com.raagmusic.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Reveal the actual background rather than approximating its animated color with an overlay.
 * Only the image has a bounded GPU layer; the cached mask never invalidates layout or decodes
 * a bitmap. The upper half stays untouched and the final pixels are completely transparent.
 */
internal fun Modifier.artworkBottomScrim(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    val mask = Brush.verticalGradient(
        0f to Color.White,
        .46f to Color.White,
        .56f to Color.White.copy(alpha = .96f),
        .68f to Color.White.copy(alpha = .78f),
        .80f to Color.White.copy(alpha = .36f),
        .90f to Color.White.copy(alpha = .10f),
        1f to Color.Transparent,
    )
    onDrawWithContent {
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
    }
}
