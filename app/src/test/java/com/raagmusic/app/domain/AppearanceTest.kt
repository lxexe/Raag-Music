package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    @Test fun modesResolveSystemAndExplicitOverrides() {
        assertFalse(ThemeMode.SYSTEM.isDark(false)); assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertFalse(ThemeMode.LIGHT.isDark(true)); assertFalse(ThemeMode.LIGHT.isDark(false))
        assertTrue(ThemeMode.DARK.isDark(false)); assertTrue(ThemeMode.AMOLED.isDark(false))
    }
    @Test fun accentsRequireSixHexDigitsAndAreOpaque() {
        assertEquals(0xFF2563EB, parseAccent(" #2563eb "))
        assertEquals(0xFF000000, parseAccent("000000"))
        assertEquals("#8B5CF6", accentHex(0xFF8B5CF6))
        listOf("", "#fff", "#FF123456", "GG0000", "#12345", "1234567").forEach { assertNull(parseAccent(it)) }
    }
    @Test fun arbitraryAccentSeedsKeepReadableTextAndButtons() {
        // Include every hue and extreme inputs, rather than testing just the default violet.
        val seeds = (0..359 step 3).map { AccentPalette.tone(it.toDouble(), 1.0, .5) } +
            listOf(0xFF000000, 0xFFFFFFFF, 0xFF808080)
        seeds.forEach { seed -> listOf(false, true).forEach { dark ->
            val background = if (dark) 0xFF0B0D14 else 0xFFF8F7FC
            val accent = AccentPalette.accent(seed, dark, background)
            assertTrue("Accent contrast for ${accentHex(seed)}", AccentPalette.contrast(accent, background) >= 4.5)
            assertTrue("Button contrast for ${accentHex(seed)}", AccentPalette.contrast(accent, AccentPalette.onAccent(accent)) >= 4.5)
        } }
    }
    @Test fun neutralCustomAccentsStayNeutral() {
        val accent = AccentPalette.accent(0xFF808080, false, 0xFFF8F7FC)
        assertEquals(accent shr 16 and 255, accent shr 8 and 255)
        assertEquals(accent shr 8 and 255, accent and 255)
    }
    @Test fun duplicateQueueKeysStayUniqueAndRetainOrdinals() {
        val keys = queueOccurrenceKeys(listOf("a", "b", "a", "a", "b"))
        assertEquals(listOf("a:0", "b:0", "a:1", "a:2", "b:1"), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf("b:0", "a:0", "a:1", "a:2", "b:1"), queueOccurrenceKeys(listOf("b", "a", "a", "a", "b")))
    }
}
