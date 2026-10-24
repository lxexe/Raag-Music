package com.raagmusic.app.data.youtube

/** Small, expiring metadata cache. Signed audio URLs must never be inserted here. */
internal class MetadataCache<K, V>(
    private val capacity: Int = 24,
    private val lifetimeMillis: Long = 5 * 60 * 1_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry<V>(val value: V, val createdAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(capacity, 0.75f, true)

    @Synchronized fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (nowMillis() - entry.createdAt !in 0 until lifetimeMillis) {
            entries.remove(key); return null
        }
        return entry.value
    }
    @Synchronized fun put(key: K, value: V) {
        entries[key] = Entry(value, nowMillis())
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun clear() { entries.clear() }
}
