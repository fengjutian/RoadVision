package com.roadvision.roadsense.pipeline

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
}
