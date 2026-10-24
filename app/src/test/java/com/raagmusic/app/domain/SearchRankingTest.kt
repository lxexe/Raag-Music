package com.raagmusic.app.domain

import org.junit.Assert.*
import org.junit.Test

class SearchRankingTest {
    private fun song(id: String, title: String, artist: String) = Track(id, title, artist, "", id)

    @Test fun exactIntentBeatsPrefixAndProviderOrderBreaksTies() {
        val cover = song("cover", "Hello cover", "Other")
        val exact = song("exact", "Hello", "Adele")
        val second = exact.copy(id = "second")
        val artist = song("artist", "Hello", "Lionel Richie")
        assertEquals(listOf(exact, second, artist, cover), rankSearchMatches(
            listOf(cover, exact, second, artist), "hello", { it.title }, { it.artist }))
        assertEquals(exact, rankSearchMatches(listOf(cover, exact), "adele hello", { it.title }, { it.artist }).first())
    }

    @Test fun normalizationPreservesNonLatinTextAndAccentsMatch() {
        assertEquals(120, searchMatchScore("beyonce", "Beyoncé"))
        assertEquals(120, searchMatchScore("नमस्ते", "नमस्ते"))
        assertEquals("नमस्ते", normalizedSearch("नमस्ते"))
        assertNotEquals(normalizedSearch("दिन"), normalizedSearch("दान"))
        assertEquals(0, searchMatchScore("arijit", "Unrelated", "Someone else"))
    }

    @Test fun suggestionsUseLocalEvidenceDeduplicateAndFilterUnrelatedTerms() {
        val result = rankedSearchSuggestions("ari", listOf("Arijit Singh", "random", "arijit singh"),
            listOf(song("1", "Ariana song", "Ariana Grande")), setOf("Arijit Singh"),
            listOf("ARijit Singh", "Ariana Grande", "random news"))
        assertEquals("Arijit Singh", result.first())
        assertEquals(result.size, result.map(::normalizedSearch).distinct().size)
        assertTrue(result.contains("Ariana Grande"))
        assertTrue(result.none { it.contains("random") })
        assertTrue(rankedSearchSuggestions("a", listOf("Artist"), emptyList()).isEmpty())
        assertFalse(rankedSearchSuggestions("adele", listOf("adele"), emptyList()).contains("adele"))
    }
}
