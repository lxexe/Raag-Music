package com.raagmusic.app.data.youtube

/** Shared across songs on one connection. Manual Play cannot bypass a server-requested pause. */
internal class ProviderFailureGate(
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wall: () -> Long = System::currentTimeMillis,
) {
    private var until = 0L
    private var verification = false
    @Synchronized fun clear() { until = 0 }
    @Synchronized fun check() {
        val remaining = until - elapsed()
        if (remaining <= 0) return
        if (verification) throw YoutubeVerificationRequiredException(retryAtEpochMillis = wall() + remaining)
        throw YoutubeRateLimitedException(remaining, wall() + remaining)
    }
    @Synchronized fun block(error: YoutubeVerificationRequiredException): Nothing {
        verification = true; until = elapsed() + 60_000
        throw YoutubeVerificationRequiredException(error, wall() + 60_000)
    }
    @Synchronized fun rateLimit(milliseconds: Long): Long {
        verification = false
        val duration = milliseconds.coerceIn(30_000, 300_000)
        until = elapsed() + duration
        return wall() + duration
    }
    @Synchronized fun block(error: YoutubeRateLimitedException): Nothing {
        val duration = error.retryAfterMillis.coerceIn(30_000, 300_000)
        throw YoutubeRateLimitedException(duration, rateLimit(duration))
    }
}
