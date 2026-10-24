package com.raagmusic.app.playback

import org.junit.Assert.*
import org.junit.Test

class NetworkRecoveryGateTest {
    @Test fun reconnectRetriesOnceAndNeverOverridesPauseOrPermanentFailure() {
        val gate = NetworkRecoveryGate()
        assertFalse(gate.validated(1, true, false))
        assertFalse(gate.validated(1, true, true))
        gate.lost(1)
        assertTrue(gate.validated(2, true, true))
        assertFalse(gate.validated(2, true, true))
        gate.lost(2)
        assertFalse(gate.validated(3, false, true))
        assertFalse(gate.validated(3, true, true))
        gate.lost(3)
        assertFalse(gate.validated(4, true, false))
        gate.lost(2) // delayed loss for an older connection cannot reset the new connection
        assertFalse(gate.validated(4, true, true))
    }
}
