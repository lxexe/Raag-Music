package com.raagmusic.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.luminance

@Composable
internal fun MusicIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = ContentColor) {
    MaterialIcon(imageVector, contentDescription, modifier, tint = themedTint(tint))
}
@Composable
internal fun MusicIcon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = ContentColor) {
    MaterialIcon(painter, contentDescription, modifier, tint = themedTint(tint))
}

@Composable
private fun themedTint(tint: Color): Color {
    if (!LocalDarkTheme.current) return tint
    if (tint == Color.Unspecified) return Color.White
    val quiet = MaterialTheme.colorScheme.background.luminance() < .5f && tint.luminance() < .7f
    return Color.White.copy(alpha = tint.alpha * if (quiet) .65f else 1f)
}
