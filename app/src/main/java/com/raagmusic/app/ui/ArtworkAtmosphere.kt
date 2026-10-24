package com.raagmusic.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.*

// Only tiny blurred textures are retained. Full-size covers remain owned by Coil.
private val atmosphereCache = object : LinkedHashMap<String, ImageBitmap>(12, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 12
}

/** Actual artwork, softly defocused and dimmed. No generated colors or colored gradients. */
@Composable
internal fun AmbientPlayerBackdrop(artwork: String, modifier: Modifier = Modifier, dark: Boolean = true) {
    val context = LocalContext.current
    val reduceMotion = LocalAppearance.current.reduceMotion
    val image by produceState(atmosphereCache[artwork], artwork) {
        atmosphereCache[artwork]?.let { value = it; return@produceState }
        if (artwork.isBlank()) { value = null; return@produceState }
        try {
            val source = if (artwork.startsWith("asset://")) "file:///android_asset/${artwork.removePrefix("asset://")}" else artwork
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(source)
                .size(48).allowHardware(false).build()) as? SuccessResult
            val texture = result?.let { withContext(Dispatchers.Default) { blurAtmosphere(it.drawable.toBitmap(48, 48)).asImageBitmap() } }
            value = texture
            if (texture != null) atmosphereCache[artwork] = texture
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
    }
    val phase = if (!reduceMotion) rememberInfiniteTransition(label = "Artwork drift").animateFloat(
        0f, (kotlin.math.PI * 2).toFloat(), infiniteRepeatable(tween(32_000, easing = LinearEasing)), label = "Drift phase") else null
    val filter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f) }) }
    Box(modifier.clipToBounds().background(if (dark) Color(0xFF101014) else Ink)) {
        Crossfade(image, Modifier.matchParentSize(), animationSpec = tween(if (reduceMotion) 0 else 900), label = "Artwork atmosphere") { texture ->
            if (texture != null) Image(texture, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium, colorFilter = filter, modifier = Modifier.fillMaxSize().graphicsLayer {
                    val angle = phase?.value ?: 0f
                    scaleX = 1.18f + .025f * kotlin.math.sin(angle)
                    scaleY = scaleX
                    translationX = size.width * .015f * kotlin.math.sin(angle)
                    translationY = size.height * .015f * kotlin.math.cos(angle)
                })
        }
        Box(Modifier.matchParentSize().background((if (dark) Color.Black else Color.White).copy(alpha = if (dark) .54f else .96f)))
    }
}

/** Three small separable box passes approximate a Gaussian blur, once per artwork off-thread. */
private fun blurAtmosphere(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    var pixels = IntArray(width * height)
    var scratch = IntArray(pixels.size)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val radius = 5
    val count = radius * 2 + 1
    repeat(3) {
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0
            for (offset in -radius..radius) {
                val pixel = pixels[y * width + (x + offset).coerceIn(0, width - 1)]
                red += pixel shr 16 and 255; green += pixel shr 8 and 255; blue += pixel and 255
            }
            scratch[y * width + x] = (0xFF shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
        }
        val swap = pixels; pixels = scratch; scratch = swap
        for (y in 0 until height) for (x in 0 until width) {
            var red = 0; var green = 0; var blue = 0
            for (offset in -radius..radius) {
                val pixel = pixels[(y + offset).coerceIn(0, height - 1) * width + x]
                red += pixel shr 16 and 255; green += pixel shr 8 and 255; blue += pixel and 255
            }
            scratch[y * width + x] = (0xFF shl 24) or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
        }
        val finalSwap = pixels; pixels = scratch; scratch = finalSwap
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
