package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class MetadataCacheTest {
    @Test fun metadataExpiresAtItsDeadline() {
        var now = 0L
        val cache = MetadataCache<String, String>(lifetimeMillis = 100, nowMillis = { now })
        cache.put("a", "metadata")
        now = 99; assertEquals("metadata", cache.get("a"))
        now = 100; assertNull(cache.get("a"))
    }
    @Test fun theLeastRecentlyUsedEntryIsEvicted() {
        val cache = MetadataCache<String, String>(capacity = 2)
        cache.put("a", "a"); cache.put("b", "b")
        cache.get("a"); cache.put("c", "c")
        assertEquals("a", cache.get("a")); assertNull(cache.get("b")); assertEquals("c", cache.get("c"))
    }
    @Test fun explicitRefreshClearsMetadata() {
        val cache = MetadataCache<String, String>()
        cache.put("a", "a"); cache.clear()
        assertNull(cache.get("a"))
    }
}
