package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun parsesFractionPrecisionMultipleStampsAndMetadata() {
        val rows = parseLrc("[ar:Artist]\n[00:02.5][00:05.125]Repeated\n[00:01.12]First\n[00:03]Whole")
        assertEquals(listOf(1120L, 2500L, 3000L, 5125L), rows.map { it.timeMs })
        assertEquals(listOf("First", "Repeated", "Whole", "Repeated"), rows.map { it.text })
    }
    @Test fun supportsOffsetsAndDoesNotTreatMetadataAsLyrics() {
        assertEquals(listOf(LyricLine(900, "Hi")), parseLrc("[offset:-100]\n[00:01.00]Hi"))
        assertTrue(parseLrc("[ti:Song]\nPlain words").isEmpty())
    }
    @Test fun clampsNegativeTimestampsAndRejectsInvalidSeconds() {
        assertEquals(listOf(LyricLine(0, "Hi")), parseLrc("[offset:-2000]\n[00:01.0]Hi\n[00:65]Bad"))
    }
    @Test fun selectsHighlightedLineAtBoundariesAndAfterSeeks() {
        val lyrics = Lyrics(listOf(LyricLine(1000, "a"), LyricLine(2000, "b")))
        assertEquals(-1, lyrics.activeIndex(0)); assertEquals(0, lyrics.activeIndex(1000))
        assertEquals(1, lyrics.activeIndex(2500)); assertEquals(0, lyrics.activeIndex(1500))
    }
    @Test fun providerSearchMatchesRecordingAndPreservesVersionLabels() {
        assertEquals("Song", lyricsTrackName("Song (Official Music Video)"))
        assertEquals("Song (Live)", lyricsTrackName("Song (Live) [Official Audio]"))
        assertTrue(lyricIdentityMatches("Song [Lyrics]", "Singer", 180, "Song", "Singer", 183.0))
        assertFalse(lyricIdentityMatches("Song", "Singer", 180, "Song (Live)", "Singer", 180.0))
        assertFalse(lyricIdentityMatches("Song", "Singer", 180, "Song", "Other singer", 240.0))
    }
    @Test fun missingEmptyAndInstrumentalLyricsAreUnavailable() {
        assertFalse(Lyrics().available)
        assertFalse(Lyrics(plain = "   ", lines = listOf(LyricLine(0, ""))).available)
        assertFalse(Lyrics(plain = "Instrumental", instrumental = true).available)
        assertTrue(Lyrics(plain = "A verse").available)
        assertTrue(Lyrics(lines = listOf(LyricLine(0, "A verse"))).available)
    }

}
