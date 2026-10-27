package com.roadvision.roadsense.tracking

import com.roadvision.roadsense.inference.Detection
import com.roadvision.roadsense.inference.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateVehicleMatcherTest {
    private val plateBox = NormalizedRect(0.4f, 0.7f, 0.6f, 0.85f)

    @Test
    fun requiresConsistentFramesBeforeConfirming() {
        val matcher = PlateVehicleMatcher(minimumConsecutiveFrames = 2, maximumGapNs = 1_000)

        val first = matcher.match(listOf(PlateCandidate(plateBox, 0.9f, 1)), listOf(vehicle(1))).single()
        assertFalse(first.confirmed)
        assertEquals(1, first.consecutiveFrames)

        val second = matcher.match(listOf(PlateCandidate(plateBox, 0.9f, 2)), listOf(vehicle(1))).single()
        assertTrue(second.confirmed)
        assertEquals(2, second.consecutiveFrames)
    }

    @Test
    fun resetsWhenDetectionGapIsTooLarge() {
        val matcher = PlateVehicleMatcher(minimumConsecutiveFrames = 2, maximumGapNs = 100)

        matcher.match(listOf(PlateCandidate(plateBox, 0.9f, 1)), listOf(vehicle(1)))
        val later = matcher.match(listOf(PlateCandidate(plateBox, 0.9f, 1_000)), listOf(vehicle(1))).single()

        assertFalse(later.confirmed)
        assertEquals(1, later.consecutiveFrames)
    }

    @Test
    fun resetsWhenPlateJumpsToAnotherRegion() {
        val matcher = PlateVehicleMatcher(minimumConsecutiveFrames = 2, maximumGapNs = 1_000)
        val moved = NormalizedRect(0.0f, 0.0f, 0.1f, 0.1f)

        matcher.match(listOf(PlateCandidate(plateBox, 0.9f, 1)), listOf(vehicle(1)))
        val jumped = matcher.match(listOf(PlateCandidate(moved, 0.9f, 2)), listOf(vehicle(1))).single()

        assertFalse(jumped.confirmed)
        assertEquals(1, jumped.consecutiveFrames)
    }

    @Test
    fun pruneDropsAssociationForFinishedTrack() {
        val matcher = PlateVehicleMatcher(minimumConsecutiveFrames = 2, maximumGapNs = 1_000)
        matcher.match(listOf(PlateCandidate(plateBox, 0.9f, 1)), listOf(vehicle(1)))

        matcher.prune(emptySet())

        val afterPrune = matcher.match(listOf(PlateCandidate(plateBox, 0.9f, 2)), listOf(vehicle(1))).single()
        assertFalse(afterPrune.confirmed)
        assertEquals(1, afterPrune.consecutiveFrames)
    }

    private fun vehicle(trackId: Long) = TrackedVehicle(
        trackId = trackId,
        detection = Detection(0, "car", 0.9f, NormalizedRect(0f, 0f, 1f, 1f), 1, 0),
        ageFrames = 1,
        missedFrames = 0,
    )
}
