package com.raagmusic.app.domain

/** Ignore white/black borders and choose a common colorful tone from a tiny artwork decode. */
fun artworkColorSeed(pixels: IntArray, fallback: Long): Long {
    val colors = mutableMapOf<Int, Int>()
    for (color in pixels) {
        if ((color ushr 24) < 128) continue
        val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        if (max < 35 || min > 225 || max - min < 25) continue
        val key = ((r / 32) shl 6) or ((g / 32) shl 3) or (b / 32)
        colors[key] = (colors[key] ?: 0) + 1
    }
    val key = colors.maxByOrNull { it.value }?.key ?: return fallback
    return 0xFF000000 or (((key shr 6) * 32 + 16).toLong() shl 16) or
        ((((key shr 3) and 7) * 32 + 16).toLong() shl 8) or ((key and 7) * 32 + 16).toLong()
}
