package com.raagmusic.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiskMetadataCacheTest {
    @Test fun expiryFutureClockAndCapacityAreEnforcedAcrossInstances() {
        val directory = Files.createTempDirectory("metadata-test").toFile()
        var now = 100_000L
        try {
            val cache = DiskMetadataCache(directory, capacity = 2, now = { now })
            cache.write("region|one", JSONObject().put("name", "one"), 1_000)
            assertEquals("one", DiskMetadataCache(directory, now = { now }).read("region|one")!!.getString("name"))
            assertNull(cache.read("other-region|one"))
            now += 1_000
            assertNull(cache.read("region|one"))
            assertNotNull(cache.read("region|one", staleForMs = 1_000))
            now = 99_999
            assertNull(cache.read("region|one", staleForMs = 1_000))
            repeat(3) { cache.write("key$it", JSONObject().put("name", it), 1_000) }
            assertTrue(directory.listFiles()!!.size <= 2)
        } finally { directory.deleteRecursively() }
    }
    @Test fun badJsonAndOversizedEntriesAreDisposable() {
        val directory = Files.createTempDirectory("metadata-test").toFile()
        try {
            val cache = DiskMetadataCache(directory)
            cache.write("key", JSONObject().put("name", "valid"), 10_000)
            directory.listFiles()!!.single().writeText("not-json")
            assertNull(cache.read("key"))
            cache.write("large", JSONObject().put("text", "x".repeat(600_000)), 10_000)
            assertNull(cache.read("large"))
        } finally { directory.deleteRecursively() }
    }
}
