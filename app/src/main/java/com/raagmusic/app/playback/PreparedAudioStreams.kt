package com.raagmusic.app.playback

import com.raagmusic.app.data.youtube.ResolvedAudioStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** Two active plays, one upcoming play, and 32 recent leases. URLs remain only in memory. */
internal class PreparedAudioStreams(
    private val fetch: suspend (String, Int) -> ResolvedAudioStream,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val epochMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val id: String, val stream: ResolvedAudioStream, val limit: Int, val at: Long)
    private data class Warmup(val reference: String, val id: String, val limit: Int, val owner: Job)
    private val lock = Any()
    private val preparing = Mutex()
    private val active = LinkedHashMap<String, Entry>()
    private val recent = LinkedHashMap<Pair<String, Int>, Entry>(32, .75f, true)
    private var warmed: Pair<String, Entry>? = null
    private var preparingWarmup: Warmup? = null
    private var generation = 0L

    suspend fun resolve(reference: String, id: String, limit: Int, warm: Boolean = false,
        requiredValidityMillis: Long = 0): ResolvedAudioStream {
        val horizon = requiredValidityMillis.coerceIn(0, WARM_TTL_MS)
        // A seek in buffered/current audio must not wait behind next-song extraction.
        synchronized(lock) { cached(reference, id, limit, warm, horizon) }?.let { return it }
        // A new selection supersedes speculative extraction, but consumes its own in-flight warmup.
        if (!warm) synchronized(lock) {
            preparingWarmup?.takeIf { it.reference != reference && (it.id != id || it.limit != limit) }?.owner
        }?.cancel()
        return preparing.withLock {
            val version = synchronized(lock) {
                cached(reference, id, limit, warm, horizon)?.let { return@withLock it }
                generation
            }
            val owner = currentCoroutineContext()[Job]!!
            if (warm) synchronized(lock) { preparingWarmup = Warmup(reference, id, limit, owner) }
            try {
                val stream = fetch(id, limit)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (generation == version) {
                        val entry = Entry(id, stream, limit, nowMillis())
                        recent[id to limit] = entry
                        while (recent.size > 32) recent.remove(recent.keys.first())
                        if (warm) warmed = reference to entry else remember(reference, entry)
                    }
                }
                stream
            } finally {
                synchronized(lock) { if (preparingWarmup?.owner === owner) preparingWarmup = null }
            }
        }
    }

    /** Called under lock. Keep a started play's encoding stable until that play ends. */
    private fun cached(reference: String, id: String, limit: Int, warm: Boolean, horizon: Long): ResolvedAudioStream? {
        active[reference]?.takeIf { valid(it, ACTIVE_TTL_MS, if (warm) horizon else 0) && (!warm || it.limit == limit) }?.let { return it.stream }
        val entry = warmed?.takeIf { it.first == reference && it.second.limit == limit && valid(it.second, WARM_TTL_MS, horizon) }
            ?.second ?: recent[id to limit]?.takeIf { valid(it, WARM_TTL_MS, maxOf(30_000, horizon)) } ?: return null
        if (warm) warmed = reference to entry
        if (!warm) { remember(reference, entry); warmed = null }
        return entry.stream
    }

    private fun valid(entry: Entry, ttl: Long, horizon: Long): Boolean =
        nowMillis() - entry.at in 0 until (ttl - horizon).coerceAtLeast(0) &&
            (entry.stream.expiresAtEpochMillis?.let { it - epochMillis() > horizon + 5_000 } ?: true)

    private fun remember(reference: String, entry: Entry) {
        active.remove(reference)
        active[reference] = entry
        while (active.size > 2) active.remove(active.keys.first())
    }

    fun resolvedLimit(reference: String): Int? = synchronized(lock) {
        active[reference]?.limit ?: warmed?.takeIf { it.first == reference }?.second?.limit
    }

    fun invalidate(reference: String? = null) = synchronized(lock) {
        if (reference == null) { active.clear(); warmed = null; recent.clear() }
        else {
            // The failed play may have been evicted from the two active leases. Its track can
            // still be in recent/warmed/another token, and none may reuse that rejected URL.
            val id = active[reference]?.id ?: warmed?.takeIf { it.first == reference }?.second?.id
                ?: runCatching { java.net.URI(reference).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } }.getOrNull()
            if (id != null) {
                active.entries.removeAll { it.value.id == id }
                recent.keys.removeAll { it.first == id }
                if (warmed?.second?.id == id) warmed = null
            }
            active.remove(reference)
            if (warmed?.first == reference) warmed = null
        }
        generation++
    }

    companion object {
        const val WARM_TTL_MS = 10 * 60 * 1_000L
        private const val ACTIVE_TTL_MS = 20 * 60 * 1_000L
    }
}

/** Never mix byte ranges from different encodings; signing parameters are excluded from disk keys. */
internal fun ResolvedAudioStream.cacheKey(id: String, reference: String): String {
    val identity = formatIdentity ?: reference
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "youtube-audio/$id/$digest"
}
