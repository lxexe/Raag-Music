package com.raagmusic.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.raagmusic.app.domain.*
import kotlinx.coroutines.*

private val moodCache = object : LinkedHashMap<String, Long>(24, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 24
}

@Composable
private fun rememberArtworkSeed(artwork: String): State<Long> {
    val context = LocalContext.current
    val fallback = LocalAppearance.current.accent
    return produceState(moodCache[artwork] ?: fallback, artwork, fallback) {
        moodCache[artwork]?.let { value = it; return@produceState }
        if (artwork.isBlank()) { value = fallback; return@produceState }
        try {
            val source = if (artwork.startsWith("asset://")) "file:///android_asset/${artwork.removePrefix("asset://")}" else artwork
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(source).size(48)
                .allowHardware(false).build()) as? SuccessResult ?: return@produceState
            value = withContext(Dispatchers.Default) {
                val bitmap = result.drawable.toBitmap(48, 48)
                val pixels = IntArray(48 * 48)
                bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48)
                artworkColorSeed(pixels, fallback)
            }
            moodCache[artwork] = value
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep the last palette while new artwork is unavailable. */ }
    }
}

@Composable
fun Modifier.dynamicPlayerBackground(artwork: String): Modifier {
    // Playback toggles preserve the current artwork tone.
    return dynamicArtworkBackground(artwork, player = true)
}

/** Non-player pages stay neutral; lyrics use a single restrained artwork tone. */
@Composable
fun Modifier.dynamicArtworkBackground(artwork: String, player: Boolean = false): Modifier {
    if (!player) return background(Ink)
    val appearance = LocalAppearance.current
    val seed by rememberArtworkSeed(artwork)
    val color by animateColorAsState(Color(AccentPalette.tone(AccentPalette.hue(seed),
        AccentPalette.saturation(seed).coerceAtMost(.15), if (LocalDarkTheme.current) .09 else .96)),
        tween(if (appearance.reduceMotion) 0 else 800), label = "Artwork tone")
    return background(color)
}

/** Preset atmosphere follows local time and the active theme, never album artwork. */
@Composable
fun Modifier.homePresetBackground(now: java.time.LocalDateTime): Modifier {
    if (LocalAppearance.current.mode == ThemeMode.AMOLED) return drawBehind { drawRect(Color.Black) }
    if (LocalAppearance.current.mode == ThemeMode.DYNAMIC) return dynamicArtworkBackground(LocalAtmosphereArtwork.current)
    val preset = remember(now.toLocalDate(), now.hour) { homeAtmosphere(now) }
    val dark = LocalDarkTheme.current
    val appearance = LocalAppearance.current
    val ink = Ink
    val primary = Lavender
    val duration = if (appearance.reduceMotion) 0 else 1_200
    val first by animateColorAsState(Color(AccentPalette.tone(preset.first, .48, if (dark) .35 else .7)), tween(duration), label = "Home dawn")
    val second by animateColorAsState(Color(AccentPalette.tone(preset.second, .42, if (dark) .3 else .72)), tween(duration), label = "Home dusk")
    val alpha = if (appearance.mode == ThemeMode.AMOLED) .12f else if (dark) .24f else .07f
    return drawBehind {
        drawRect(ink)
        drawRect(Brush.verticalGradient(listOf(primary.copy(alpha = alpha), Color.Transparent), endY = size.height * .75f))
        drawRect(Brush.radialGradient(listOf(first.copy(alpha = alpha), Color.Transparent), center = Offset(0f, size.height * .15f), radius = size.width * 1.3f))
        drawRect(Brush.radialGradient(listOf(second.copy(alpha = alpha), Color.Transparent), center = Offset(size.width, size.height * .52f), radius = size.width * 1.2f))
    }
}
