package com.raagmusic.app.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.raagmusic.app.data.youtube.YoutubeExtractionException

@UnstableApi
internal fun PlaybackException.httpResponseCode(): Int? = generateSequence<Throwable>(this) { it.cause }
    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

@UnstableApi
internal fun PlaybackException.isRecoverableNetworkError(): Boolean =
    generateSequence<Throwable>(this) { it.cause }.any { it is CachedAudioFormatChangedException } ||
    httpResponseCode() in setOf(403, 408, 429, 500, 502, 503, 504) || hasNetworkCause() ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

@UnstableApi
internal fun playbackErrorMessage(error: PlaybackException): String {
    val extraction = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<YoutubeExtractionException>().firstOrNull()
    return when {
        extraction != null -> extraction.message ?: "YouTube couldn't provide this track."
        error.httpResponseCode() == 403 -> "YouTube rejected the audio stream. Tap Play to try a fresh stream."
        error.isRecoverableNetworkError() -> "Playback interrupted. Check your connection, then tap Play."
        else -> "This track couldn't be played. Try another track."
    }
}
