package com.raagmusic.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Two attempts total. Cancellation always wins over retrying stale work. */
internal suspend fun <T> retryApiOnce(backoffMillis: Long = 180, shouldRetry: (Exception) -> Boolean = { true }, block: suspend () -> T): T {
    try { return block() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        if (!shouldRetry(e)) throw e
        currentCoroutineContext().ensureActive()
        delay(backoffMillis)
    }
    return block()
}
