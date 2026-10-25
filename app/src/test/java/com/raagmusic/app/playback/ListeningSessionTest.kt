package com.raagmusic.app.playback

import org.junit.Assert.*
import org.junit.Test

class ListeningSessionTest {
    @Test fun completionRequiresActualHeardTimeAndIsRecordedOnlyOnce() {
        val session = ListeningSession()
        session.transition("a"); session.started("a"); session.duration("a", 200_000)
        session.heard("a", 20_000)
        assertNull(session.complete()) // A seek to the end cannot manufacture a completion.
        session.heard("a", 140_000)
        assertEquals("a", session.complete())
        assertNull(session.complete())
        session.transition("a", repeated = true); session.started("a"); session.duration("a", 200_000)
        assertNull(session.complete())
    }
    @Test fun replayingACompletedSongBeginsAnotherListenWithoutManufacturingCompletion() {
        val session = ListeningSession()
        session.transition("a"); session.started("a"); session.duration("a", 10_000); session.heard("a", 9_000)
        assertEquals("a", session.complete())
        assertTrue(session.restartCompleted("a")); assertFalse(session.restartCompleted("a"))
        session.started("a"); session.duration("a", 10_000)
        assertNull(session.complete())
        session.heard("a", 9_000); assertEquals("a", session.complete())
    }
    @Test fun failedOrUnheardSongsAreNotNegativeTasteSignals() {
        val session = ListeningSession()
        session.transition("a"); assertFalse(session.earlySkip("a"))
        session.started("a"); session.heard("a", 4_000); session.failed("a")
        assertFalse(session.earlySkip("a"))
        session.transition("b"); session.started("b"); session.heard("b", 3_000)
        assertTrue(session.earlySkip("b")); assertFalse(session.earlySkip("b"))
    }
    @Test fun aSeekBackAfterLongListeningIsNotAnEarlySkip() {
        val session = ListeningSession()
        session.transition("a"); session.started("a"); session.heard("a", 60_000)
        assertFalse(session.earlySkip("a"))
    }
    @Test fun bufferedFailuresCanRecoverAndCompleteWithEnoughRealPlayback() {
        val session = ListeningSession()
        session.transition("a"); session.started("a"); session.duration("a", 20_000)
        session.heard("a", 5_000); session.failed("a"); session.heard("a", 13_000)
        assertEquals("a", session.complete())
    }
}
