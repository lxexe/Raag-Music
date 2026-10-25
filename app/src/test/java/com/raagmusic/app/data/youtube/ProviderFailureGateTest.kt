package com.raagmusic.app.data.youtube

import org.junit.Assert.*
import org.junit.Test

class ProviderFailureGateTest {
    @Test fun blockedRequestsDoNotExtendCooldownAndExpiryAllowsAnotherAttempt() {
        var time = 1_000L
        val gate = ProviderFailureGate({ time }, { 100_000 + time })
        val first = assertThrows(YoutubeVerificationRequiredException::class.java) { gate.block(YoutubeVerificationRequiredException()) }
        time += 30_000
        val next = assertThrows(YoutubeVerificationRequiredException::class.java) { gate.check() }
        assertEquals(first.retryAtEpochMillis, next.retryAtEpochMillis)
        time += 30_000
        gate.check()
    }
    @Test fun rateLimitIsBoundedAndANewConnectionCanClearIt() {
        var time = 0L
        val gate = ProviderFailureGate({ time }, { 500_000 + time })
        val error = assertThrows(YoutubeRateLimitedException::class.java) { gate.block(YoutubeRateLimitedException(900_000)) }
        assertEquals(300_000L, error.retryAfterMillis)
        time += 1_000
        assertThrows(YoutubeRateLimitedException::class.java) { gate.check() }
        gate.clear(); gate.check()
        assertEquals(561_000L, gate.rateLimit(60_000))
        assertThrows(YoutubeRateLimitedException::class.java) { gate.check() }
        gate.clear(); gate.check()
        assertEquals(30_000L, assertThrows(YoutubeRateLimitedException::class.java) { gate.block(YoutubeRateLimitedException(1)) }.retryAfterMillis)
    }
}
