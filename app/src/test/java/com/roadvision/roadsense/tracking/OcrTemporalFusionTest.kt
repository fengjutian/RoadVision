package com.roadvision.roadsense.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTemporalFusionTest {
    @Test
    fun requiresRepeatedConfidentObservation() {
        val fusion = OcrTemporalFusion(minimumObservations = 2, acceptanceConfidence = 0.7f)
        assertTrue(fusion.add(OcrObservation(1, "A12345", 0.8f, 1)).uncertain)
        val fused = fusion.add(OcrObservation(1, "a12345", 0.9f, 2))
        assertFalse(fused.uncertain)
        assertEquals("A12345", fused.text)
    }

    @Test
    fun pruneDropsHistoryForFinishedTracks() {
        val fusion = OcrTemporalFusion(minimumObservations = 2, acceptanceConfidence = 0.7f)
        assertTrue(fusion.add(OcrObservation(1, "A12345", 0.9f, 1)).uncertain)
        assertEquals(1, fusion.trackedTrackCount())

        fusion.prune(setOf(2))

        assertEquals(0, fusion.trackedTrackCount())
        assertTrue(fusion.add(OcrObservation(1, "A12345", 0.9f, 2)).uncertain)
    }
}
