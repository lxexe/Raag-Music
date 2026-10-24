package com.raagmusic.app.playback

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException

class PlaybackRecoveryPolicyTest {
    @Test fun retriesAreBoundedAndUseBackoff() {
        val policy = PlaybackRecoveryPolicy()
        assertEquals(1_000L, policy.nextDelayMillis(true))
        assertEquals(2_000L, policy.nextDelayMillis(true))
        assertNull(policy.nextDelayMillis(true))
        assertNull(policy.nextDelayMillis(true))
    }
    @Test fun permanentExtractionFailuresAreNotRetried() {
        val policy = PlaybackRecoveryPolicy()
        assertNull(policy.nextDelayMillis(false))
        assertEquals(1_000L, policy.nextDelayMillis(true))
    }
    @Test fun aNewTrackGetsItsOwnRetryBudget() {
        val policy = PlaybackRecoveryPolicy()
        repeat(3) { policy.nextDelayMillis(true) }
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMillis(true))
    }
    @Test fun nestedNetworkExceptionsAreRecognized() {
        assertTrue(RuntimeException("Player", IllegalStateException(UnknownHostException())).hasNetworkCause())
        assertFalse(IllegalArgumentException("Bad track").hasNetworkCause())
    }
    @Test fun rebuildingTheSameTrackCannotResetTheAutomaticRetryBudget() {
        val policy = PlaybackRecoveryPolicy()
        policy.onTrackTransition("first")
        assertNotNull(policy.nextDelayMillis(true))
        policy.onTrackTransition("first")
        assertNotNull(policy.nextDelayMillis(true))
        policy.onTrackTransition("first")
        assertNull(policy.nextDelayMillis(true))
        policy.reset() // Explicit Play starts one new bounded recovery cycle.
        assertNotNull(policy.nextDelayMillis(true))
        policy.onTrackTransition("second")
        assertEquals(1_000L, policy.nextDelayMillis(true))
    }

}
