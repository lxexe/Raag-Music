package com.raagmusic.app.domain

import androidx.compose.runtime.Immutable
import kotlin.math.*

@Immutable
data class ThemePreferences(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val colors: ThemeColors = ThemeColors.RAAG,
    val accent: Long = 0xFF8B5CF6,
    val surfaces: SurfaceStyle = SurfaceStyle.TONAL,
    val corners: CornerStyle = CornerStyle.SOFT,
    val reduceMotion: Boolean = false,
    val motion: MotionLevel = MotionLevel.SUBTLE,
    val haptics: Boolean = true,
    val effects: Boolean = true,
    val density: ContentDensity = ContentDensity.COMFORTABLE,
    val playerStyle: PlayerStyle = PlayerStyle.COMPACT,
    val miniFavorite: Boolean = true,
    val lyricStyle: LyricStyle = LyricStyle.DEFAULT,
)
enum class MotionLevel(val label: String) { OFF("Off"), SUBTLE("Subtle"), FULL("Full") }
enum class ContentDensity(val label: String) { COMFORTABLE("Comfortable"), COMPACT("Compact") }
enum class PlayerStyle(val label: String, val description: String) {
    APPLE("Apple Music", "Oversized artwork, bold transport and Playing Next"),
    IMMERSIVE("Immersive", "Edge-to-edge artwork with floating controls"),
    COMPACT("Default", "Large artwork and anchored playback controls"),
    VINYL("Vinyl", "A spinning record with album-label artwork"),
    POSTER("Poster", "Tall artwork with a cinematic fade"),
    WAVE("Wave", "Circular artwork with a live audio waveform"),
}
enum class LyricStyle(val label: String) { DEFAULT("Default"), APPLE("Apple Music") }
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark"), DYNAMIC("Dynamic"), AMOLED("AMOLED") }
enum class ThemeColors(val label: String) { RAAG("Raag Music"), WALLPAPER("Material You"), CUSTOM("Custom accent") }
enum class SurfaceStyle(val label: String) { TONAL("Tonal"), GLASS("Glass") }
enum class CornerStyle(val label: String, val radius: Int) { COMPACT("Compact", 14), SOFT("Soft", 22), ROUND("Round", 30) }
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; else -> true }
fun parseAccent(hex: String): Long? = hex.trim().removePrefix("#").takeIf { Regex("[a-fA-F0-9]{6}").matches(it) }?.toLong(16)?.or(0xFF000000)
fun accentHex(color: Long): String = "#%06X".format(color and 0xFFFFFF)

/** Small portable palette math, including contrast correction for arbitrary custom seeds. */
object AccentPalette {
    fun hue(color: Long): Double {
        val r = (color shr 16 and 255) / 255.0; val g = (color shr 8 and 255) / 255.0; val b = (color and 255) / 255.0
        val max = maxOf(r,g,b); val min = minOf(r,g,b); val delta = max - min
        if (delta == 0.0) return 270.0
        return ((when (max) { r -> (g - b) / delta; g -> (b - r) / delta + 2; else -> (r - g) / delta + 4 }) * 60 + 360) % 360
    }
    fun saturation(color: Long): Double {
        val channels = listOf(16, 8, 0).map { (color shr it and 255) / 255.0 }
        val max = channels.max(); val min = channels.min(); val delta = max - min
        val lightness = (max + min) / 2
        return if (delta == 0.0) 0.0 else delta / (1 - abs(2 * lightness - 1))
    }
    fun tone(hue: Double, saturation: Double, lightness: Double): Long {
        val c = (1 - abs(2 * lightness - 1)) * saturation
        val h = ((hue % 360 + 360) % 360) / 60
        val x = c * (1 - abs(h % 2 - 1)); val m = lightness - c / 2
        val rgb = when (h.toInt()) { 0 -> listOf(c,x,0.0); 1 -> listOf(x,c,0.0); 2 -> listOf(0.0,c,x); 3 -> listOf(0.0,x,c); 4 -> listOf(x,0.0,c); else -> listOf(c,0.0,x) }
        val channels = rgb.map { ((it + m) * 255).roundToInt().coerceIn(0,255).toLong() }
        return 0xFF000000 or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }
    fun contrast(a: Long, b: Long): Double {
        fun luminance(color: Long): Double {
            fun linear(shift: Int): Double { val c = (color shr shift and 255) / 255.0; return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4) }
            return .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
        }
        val x = luminance(a); val y = luminance(b)
        return (max(x,y) + .05) / (min(x,y) + .05)
    }
    fun accent(seed: Long, dark: Boolean, background: Long): Long {
        var lightness = if (dark) .78 else .38
        repeat(30) {
            val color = tone(hue(seed), saturation(seed).coerceAtMost(.75), lightness)
            if (contrast(color, background) >= 4.5) return color
            lightness = (lightness + if (dark) .02 else -.02).coerceIn(.03,.97)
        }
        return if (dark) 0xFFFFFFFF else 0xFF000000
    }
    fun onAccent(color: Long): Long = if (contrast(color, 0xFF000000) >= contrast(color, 0xFFFFFFFF)) 0xFF000000 else 0xFFFFFFFF
}
