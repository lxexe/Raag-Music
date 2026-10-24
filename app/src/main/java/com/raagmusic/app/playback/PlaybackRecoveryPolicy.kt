package com.raagmusic.app.playback

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException

/** A per-track budget prevents permanent 403s or a disconnected device causing a retry loop. */
class PlaybackRecoveryPolicy(private val maxRetries: Int = 2, private val baseDelayMillis: Long = 1_000L) {
    private var attempts = 0
    private var trackId: String? = null
    fun reset() { attempts = 0 }
    fun onTrackTransition(id: String?, repeated: Boolean = false): Boolean {
        val changed = trackId != id || repeated
        if (changed) reset()
        trackId = id
        return changed
    }
    fun nextDelayMillis(retryable: Boolean): Long? {
        if (!retryable || attempts >= maxRetries) return null
        return baseDelayMillis shl attempts++
    }
}

internal fun Throwable.hasNetworkCause(): Boolean = generateSequence(this) { it.cause }
    .any { it is UnknownHostException || it is ConnectException || it is SocketException || it is InterruptedIOException }
