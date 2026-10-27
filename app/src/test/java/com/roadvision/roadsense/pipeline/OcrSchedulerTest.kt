package com.roadvision.roadsense.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSchedulerTest {
    @Test
    fun enforcesQualityIntervalAndAttemptLimit() {
        val scheduler = OcrScheduler(minimumIntervalNs = 10, maximumAttemptsPerTrack = 2)
        assertFalse(scheduler.shouldRun(1, 0, false))
        assertTrue(scheduler.shouldRun(1, 0, true))
        assertFalse(scheduler.shouldRun(1, 5, true))
        assertTrue(scheduler.shouldRun(1, 10, true))
        assertFalse(scheduler.shouldRun(1, 20, true))
    }

    @Test
    fun pruneDropsStateForFinishedTracks() {
        val scheduler = OcrScheduler(minimumIntervalNs = 10, maximumAttemptsPerTrack = 2)
        assertTrue(scheduler.shouldRun(1, 0, true))
        assertTrue(scheduler.shouldRun(2, 0, true))
        assertEquals(2, scheduler.trackedTrackCount())

        scheduler.prune(setOf(2))

        assertEquals(1, scheduler.trackedTrackCount())
        // Track 1 was pruned, so its interval and attempt budget start over.
        assertTrue(scheduler.shouldRun(1, 1, true))
    }
}
