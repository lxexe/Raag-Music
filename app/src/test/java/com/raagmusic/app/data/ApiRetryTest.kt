package com.raagmusic.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ApiRetryTest {
    @Test fun permanentFailureIsSurfacedWithoutRepeatingTheRequest() = runBlocking {
        var attempts = 0
        try {
            retryApiOnce(backoffMillis = 0, shouldRetry = { it !is IllegalArgumentException }) {
                attempts++; throw IllegalArgumentException("unavailable")
            }
            fail()
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, attempts)
    }
    @Test fun transientFailureGetsOneRetry() = runBlocking {
        var attempts = 0
        val result = retryApiOnce(backoffMillis = 0) { if (++attempts == 1) throw IOException("temporary"); "ok" }
        assertEquals("ok", result); assertEquals(2, attempts)
    }
    @Test fun repeatedFailureIsSurfacedAfterTwoAttempts() = runBlocking {
        var attempts = 0
        try { retryApiOnce(backoffMillis = 0) { attempts++; throw IOException("unavailable") }; fail() }
        catch (e: IOException) { assertEquals("unavailable", e.message) }
        assertEquals(2, attempts)
    }
    @Test fun cancellationNeverRetries() = runBlocking {
        var attempts = 0
        try { retryApiOnce(backoffMillis = 0) { attempts++; throw CancellationException("stale") }; fail() }
        catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }
}
