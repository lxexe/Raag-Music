package com.raagmusic.app.domain

/** The saved deadline survives UI recreation; active countdowns use a monotonic clock. */
data class SleepTimerSpec(val deadlineEpochMs: Long = 0, val finishTrackId: String? = null) {
    val active: Boolean get() = deadlineEpochMs > 0 || finishTrackId != null
}

class RunningSleepTimer(val spec: SleepTimerSpec, wallNow: Long, private val elapsedStart: Long) {
    private val initialRemaining = (spec.deadlineEpochMs - wallNow).coerceIn(0, 180 * 60_000L)
    fun remainingMs(elapsedNow: Long): Long = (initialRemaining - (elapsedNow - elapsedStart).coerceAtLeast(0)).coerceAtLeast(0)
}

fun sleepCountdownLabel(remainingMs: Long): String {
    val seconds = ((remainingMs.coerceAtLeast(0) + 999) / 1000)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
