package com.roadvision.roadsense.tracking

import com.roadvision.roadsense.inference.Detection
import com.roadvision.roadsense.inference.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTrackerTest {
    @Test
    fun preservesIdAcrossDroppedFrameAndExpiresTrack() {
        val tracker = VehicleTracker(minimumIou = 0.2f, maximumMissedFrames = 1, maximumIdleNs = Long.MAX_VALUE)
        val first = tracker.update(listOf(detection(0.1f, 1)), 1).single()
        assertTrue(tracker.update(emptyList(), 2).single().missedFrames == 1)
        val recovered = tracker.update(listOf(detection(0.12f, 3)), 3).single()
        assertEquals(first.trackId, recovered.trackId)
        tracker.update(emptyList(), 4)
        assertTrue(tracker.update(emptyList(), 5).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonMonotonicTime() {
        val tracker = VehicleTracker()
        tracker.update(emptyList(), 2)
        tracker.update(emptyList(), 1)
    }

    private fun detection(left: Float, timestamp: Long) = Detection(
        0, "car", 0.9f, NormalizedRect(left, 0.1f, left + 0.3f, 0.5f), timestamp, timestamp,
    )
}
