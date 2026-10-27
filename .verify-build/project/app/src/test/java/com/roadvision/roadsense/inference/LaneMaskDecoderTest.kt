package com.roadvision.roadsense.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneMaskDecoderTest {
    private fun mask(width: Int, height: Int, bands: List<(Int) -> Int?>, score: Float = 0.9f): FloatArray {
        val values = FloatArray(width * height)
        for (y in 0 until height) {
            for (band in bands) {
                band(y)?.let { x -> if (x in 0 until width) values[y * width + x] = score }
            }
        }
        return values
    }

    @Test
    fun twoBandsDecodeToTwoLanesInsteadOfTheirAverage() {
        val decoder = LaneMaskDecoder(width = 64, height = 16, threshold = 0.5f, rowStride = 4)
        val left = { y: Int -> 16 + y / 4 }
        val right = { y: Int -> 48 - y / 4 }

        val result = decoder.decode(mask(64, 16, listOf(left, right)), 1, 2)

        assertEquals(2, result.lanes.size)
        result.lanes.forEach { lane -> assertEquals(4, lane.size) }
        assertTrue(result.lanes[0].first().x < 0.5f)
        assertTrue(result.lanes[1].first().x > 0.5f)
        assertEquals(0.9f, result.confidence, 0.001f)
    }

    @Test
    fun singleBandStaysASinglePolyline() {
        val decoder = LaneMaskDecoder(width = 64, height = 16, threshold = 0.5f, rowStride = 4)
        val result = decoder.decode(mask(64, 16, listOf { _ -> 32 }), 1, 2)

        assertEquals(1, result.lanes.size)
        assertEquals(4, result.lanes.single().size)
        assertEquals(0.5f, result.lanes.single().first().x, 0.001f)
    }

    @Test
    fun bandShorterThanTheMinimumIsDropped() {
        val decoder = LaneMaskDecoder(
            width = 64, height = 16, threshold = 0.5f, rowStride = 4, minimumPoints = 3,
        )
        val values = mask(64, 16, listOf { y -> if (y == 0 || y == 4) 32 else null })

        val result = decoder.decode(values, 1, 2)

        assertTrue(result.lanes.isEmpty())
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun respectsTheLaneLimit() {
        val decoder = LaneMaskDecoder(
            width = 128, height = 16, threshold = 0.5f, rowStride = 4, maxLanes = 2,
        )
        val bands = listOf({ _: Int -> 16 }, { _: Int -> 64 }, { _: Int -> 112 })

        val result = decoder.decode(mask(128, 16, bands), 1, 2)

        assertEquals(2, result.lanes.size)
    }
}
