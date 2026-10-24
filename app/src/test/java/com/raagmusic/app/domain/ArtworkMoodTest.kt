package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class ArtworkMoodTest {
    @Test fun artworkColorsIgnoreNeutralBordersAndTransparentPixels() {
        val red = 0xFFDC2828.toInt()
        val seed = artworkColorSeed(IntArray(100) { if (it < 70) 0xFFFFFFFF.toInt() else red } + intArrayOf(0x00FF0000), 0xFF8B5CF6)
        assertTrue(AccentPalette.hue(seed) < 20 || AccentPalette.hue(seed) > 340)
        assertEquals(0xFF8B5CF6, artworkColorSeed(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0xFF8B5CF6))
    }
}
