package com.raagmusic.app.playback

import androidx.media3.common.PlaybackException
import com.raagmusic.app.data.youtube.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackErrorsTest {
    private class FixtureError(cause: Throwable, code: Int) : PlaybackException("Source failed", cause, code, android.os.Bundle.EMPTY, 0)
    private fun wrapped(cause: Throwable) = FixtureError(java.io.IOException(cause), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
    @Test fun providerRestrictionsTakePrecedenceOverNetworkWrappersAndNeverAutomaticallyRetry() {
        val verification = wrapped(YoutubeVerificationRequiredException(retryAtEpochMillis = 100_000))
        assertFalse(verification.isRecoverableNetworkError())
        assertEquals(PlaybackFailureKind.VERIFICATION, playbackFailure(verification).kind)
        assertEquals(100_000L, playbackFailure(verification).retryAtEpochMillis)
        val limited = wrapped(YoutubeRateLimitedException(60_000, 200_000))
        assertFalse(limited.isRecoverableNetworkError())
        assertEquals(PlaybackFailureKind.RATE_LIMIT, playbackFailure(limited).kind)
        assertEquals(200_000L, playbackFailure(limited).retryAtEpochMillis)
    }
    @Test fun transientNetworkFailuresRemainRecoverableAndDetailsNeverLeakProviderUrls() {
        val network = wrapped(java.net.SocketTimeoutException())
        assertTrue(network.isRecoverableNetworkError())
        assertEquals(PlaybackFailureKind.CONNECTION, playbackFailure(network).kind)
        val rejected = FixtureError(YoutubeExtractionException("https://private.example/stream?token=secret"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        assertFalse(playbackFailure(rejected).message.contains("secret"))
        assertEquals(PlaybackFailureKind.UNAVAILABLE, playbackFailure(rejected).kind)
    }
}
