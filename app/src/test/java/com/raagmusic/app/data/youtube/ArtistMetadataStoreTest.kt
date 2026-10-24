package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ArtistMetadataStoreTest {
    @Test fun portraitsAndCreditsSurviveRestartButExpire() {
        val directory = Files.createTempDirectory("raag-artists").toFile()
        var now = 1_000L
        try {
            val first = ArtistMetadataStore(directory) { now }
            first.put(ArtistProfile("Arijit Singh", "arijit-photo", channelUrl = "arijit-channel"))
            first.put(ArtistProfile("Shreya Ghoshal", "shreya-photo", channelUrl = "shreya-channel"))
            first.put("abcdefghijk", listOf("Arijit Singh", "Shreya Ghoshal"))
            val restarted = ArtistMetadataStore(directory) { now }
            assertEquals("arijit-photo", restarted.profile(" ARIJIT SINGH ")?.imageUrl)
            assertEquals("shreya-photo", restarted.profile("Shreya Ghoshal")?.imageUrl)
            assertEquals(listOf("Arijit Singh", "Shreya Ghoshal"), restarted.artists("abcdefghijk"))
            now += ArtistMetadataStore.LIFETIME
            assertNull(restarted.profile("Arijit Singh"))
            assertNull(restarted.artists("abcdefghijk"))
            assertNull(ArtistMetadataStore(directory) { now }.profile("Shreya Ghoshal"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun missingPortraitsAndEmptyCreditsDoNotPoisonPersistentCache() {
        val cache = ArtistMetadataStore(null)
        cache.put(ArtistProfile("Missing"))
        cache.put("abcdefghijk", emptyList())
        assertNull(cache.profile("Missing"))
        assertNull(cache.artists("abcdefghijk"))
    }
    @Test fun corruptCacheNeverPreventsNewMetadataBeingSaved() {
        val directory = Files.createTempDirectory("raag-artists").toFile()
        try {
            directory.resolve("artist-metadata-v1.json").writeText("broken-json")
            val cache = ArtistMetadataStore(directory)
            cache.put(ArtistProfile("Singer", "portrait"))
            assertEquals("portrait", ArtistMetadataStore(directory).profile("Singer")?.imageUrl)
        } finally { directory.deleteRecursively() }
    }
}
