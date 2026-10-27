package com.roadvision.roadsense.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceContractTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteOutput() {
        OutputValidator.validateFinite(floatArrayOf(0f, Float.NaN), "test")
    }

    @Test
    fun mapsLetterboxCoordinatesRoundTrip() {
        val mapper = LetterboxTransform(1920, 1080, 640, 640)
        val source = NormalizedRect(0.1f, 0.2f, 0.8f, 0.9f)
        val restored = mapper.modelToSource(mapper.sourceToModel(source))
        assertEquals(source.left, restored.left, 0.001f)
        assertEquals(source.top, restored.top, 0.001f)
        assertEquals(source.right, restored.right, 0.001f)
        assertEquals(source.bottom, restored.bottom, 0.001f)
    }

    @Test
    fun vehicleDecoderAppliesThresholdAndNms() {
        val decoder = VehicleOutputDecoder(mapOf(0 to "car"), 0.5f, 0.4f)
        val result = decoder.decode(
            arrayOf(
                floatArrayOf(0.1f, 0.1f, 0.5f, 0.5f, 0.9f, 0f),
                floatArrayOf(0.11f, 0.11f, 0.51f, 0.51f, 0.8f, 0f),
                floatArrayOf(0.6f, 0.6f, 0.9f, 0.9f, 0.4f, 0f),
            ), 1, 2,
        )
        assertEquals(1, result.size)
        assertEquals(0.9f, result.single().confidence, 0.001f)
    }

    @Test
    fun laneDecoderMarksEmptyResultUnavailable() {
        val result = LaneMaskDecoder(4, 4, 0.8f, 1).decode(FloatArray(16), 1, 2)
        assertTrue(result.lanes.isEmpty())
        assertEquals(0f, result.confidence, 0f)
    }
}
