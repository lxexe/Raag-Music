package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class MusicSearchSuggestionsTest {
    @Test fun onlyMusicQueryEndpointsBecomeSuggestions() {
        val response = """{"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
            {"searchSuggestionRenderer":{"navigationEndpoint":{"searchEndpoint":{"query":"Arijit Singh"}}}},
            {"searchSuggestionRenderer":{"navigationEndpoint":{"searchEndpoint":{"query":"arijit singh"}}}},
            {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"Not a query"}]}}},
            {"searchSuggestionRenderer":{"navigationEndpoint":{"searchEndpoint":{"query":"  arijit songs  "}}}}
        ]}}]}"""
        assertEquals(listOf("Arijit Singh", "arijit songs"), MusicSearchSuggestions.parse(response))
        assertTrue(MusicSearchSuggestions.parse("{}").isEmpty())
    }
}
