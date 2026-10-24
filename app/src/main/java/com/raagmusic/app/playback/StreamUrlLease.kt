package com.raagmusic.app.playback

/**
 * One active play's URL, held in memory only. Reopening a source for a seek should
 * not scrape YouTube again. A new play token, TTL expiry or error forces extraction.
 */
class StreamUrlLease(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lifetimeMillis: Long = 20 * 60 * 1_000,
) {
    private data class Entry(val playReference: String, val url: String, val createdAt: Long)
    private val lock = Any()
    private var entry: Entry? = null
    private var generation = 0L

    fun resolve(playReference: String, fetch: () -> String): String {
        val version = synchronized(lock) {
            val current = entry
            if (current != null && current.playReference == playReference &&
                nowMillis() - current.createdAt in 0 until lifetimeMillis) return current.url
            generation
        }
        // Do not hold the lock during IO: service destruction/error handling runs on Main.
        val url = fetch()
        synchronized(lock) {
            if (generation == version) entry = Entry(playReference, url, nowMillis())
        }
        return url
    }

    fun invalidate() { synchronized(lock) { entry = null; generation++ } }
}
