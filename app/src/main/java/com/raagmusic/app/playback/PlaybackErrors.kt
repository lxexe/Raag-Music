package com.raagmusic.app.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.raagmusic.app.data.youtube.YoutubeExtractionException
import com.raagmusic.app.data.youtube.YoutubeVerificationRequiredException
import com.raagmusic.app.data.youtube.YoutubeRateLimitedException

/** Public UI state never contains response bodies, cookies or signed media URLs. */
enum class PlaybackFailureKind { CONNECTION, VERIFICATION, RATE_LIMIT, STREAM, UNAVAILABLE, OTHER }
data class PlaybackFailure(val kind: PlaybackFailureKind, val title: String, val message: String, val retryAtEpochMillis: Long? = null)

@UnstableApi
internal fun PlaybackException.httpResponseCode(): Int? = generateSequence<Throwable>(this) { it.cause }
    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

@UnstableApi
internal fun PlaybackException.isRecoverableNetworkError(): Boolean {
    val causes = generateSequence<Throwable>(this) { it.cause }.take(16).toList()
    if (causes.any { it is YoutubeVerificationRequiredException || it is YoutubeRateLimitedException } || httpResponseCode() == 429) return false
    return causes.any { it is CachedAudioFormatChangedException } ||
        httpResponseCode() in setOf(403, 408, 500, 502, 503, 504) || hasNetworkCause() ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
}

@UnstableApi
internal fun playbackFailure(error: PlaybackException): PlaybackFailure {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<YoutubeVerificationRequiredException>().firstOrNull()?.let {
        return PlaybackFailure(PlaybackFailureKind.VERIFICATION, "YouTube needs verification",
            "YouTube is restricting this connection. Try another network, wait before retrying, or open this song in YouTube.", it.retryAtEpochMillis)
    }
    causes.filterIsInstance<YoutubeRateLimitedException>().firstOrNull()?.let {
        return PlaybackFailure(PlaybackFailureKind.RATE_LIMIT, "Let's give YouTube a moment", it.message.orEmpty(), it.retryAtEpochMillis)
    }
    if (error.httpResponseCode() == 429) return PlaybackFailure(PlaybackFailureKind.RATE_LIMIT, "Too many requests",
        "YouTube is limiting this connection. Wait a moment before trying again.")
    if (error.hasNetworkCause() || error.errorCode in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)) {
        return PlaybackFailure(PlaybackFailureKind.CONNECTION, "Connection interrupted", "Check your connection, then try again. Your queue and position are kept.")
    }
    if (error.httpResponseCode() == 403) return PlaybackFailure(PlaybackFailureKind.STREAM, "This stream expired or was rejected",
        "Try a fresh stream. If it still fails, open the song in YouTube or try another track.")
    val extraction = causes.filterIsInstance<YoutubeExtractionException>().firstOrNull()
    if (extraction != null) return PlaybackFailure(PlaybackFailureKind.UNAVAILABLE, "This song isn't available", "Try another song or open it in YouTube.")
    return PlaybackFailure(PlaybackFailureKind.OTHER, "Playback stopped", "Try again or choose another song. Your queue is kept.")
}

@UnstableApi
internal fun playbackErrorMessage(error: PlaybackException): String = playbackFailure(error).message
