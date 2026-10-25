package com.raagmusic.app.playback

/** Actual heard time, not seek position, drives local taste signals. No playback URLs are retained. */
internal class ListeningSession {
    private var id: String? = null
    private var heardMs = 0L
    private var durationMs = 0L
    private var started = false
    private var failed = false
    private var completionRecorded = false
    private var skipRecorded = false
    fun transition(nextId: String?, repeated: Boolean = false) {
        if (id == nextId && !repeated) return
        id = nextId; heardMs = 0; durationMs = 0; started = false; failed = false
        completionRecorded = false; skipRecorded = false
    }
    fun started(trackId: String) { if (id == null) transition(trackId); if (id == trackId) started = true }
    fun heard(trackId: String?, milliseconds: Long) { if (trackId != null && id == trackId && started) heardMs += milliseconds.coerceAtLeast(0) }
    fun duration(trackId: String?, milliseconds: Long) { if (trackId == id && milliseconds > 0) durationMs = milliseconds }
    fun failed(trackId: String?) { if (trackId == id) failed = true }
    fun earlySkip(trackId: String?): Boolean {
        if (trackId == null || trackId != id || !started || failed || skipRecorded || heardMs !in 1..29_999) return false
        skipRecorded = true
        return true
    }
    fun restartCompleted(trackId: String?): Boolean {
        if (trackId != id || !completionRecorded) return false
        transition(trackId, repeated = true)
        return true
    }
    fun earlySkipCurrent(): String? = id?.takeIf { earlySkip(it) }
    fun complete(): String? {
        if (!started || completionRecorded || durationMs <= 0 || heardMs < durationMs * .8) return null
        completionRecorded = true
        return id
    }
}
