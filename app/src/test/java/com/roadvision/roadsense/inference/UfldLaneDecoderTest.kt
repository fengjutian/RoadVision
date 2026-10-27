package com.roadvision.roadsense.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UfldLaneDecoderTest {
    private val numCells = 200
    private val numLanes = 4
    private val rows = 18
    private val anchors = FloatArray(rows) { (121 + it * 9) / 288f }

    private fun logits(build: (row: Int, lane: Int) -> Pair<Int, Float>?): FloatArray {
        val values = FloatArray((numCells + 1) * rows * numLanes)
        for (row in 0 until rows) for (lane in 0 until numLanes) {
            // A sharp peak, the way a trained UFLD head behaves; flat logits are a separate test.
            for (cell in 0..numCells) values[(cell * rows + row) * numLanes + lane] = -30f
            val peak = build(row, lane) ?: (numCells to 30f)
            values[(peak.first * rows + row) * numLanes + lane] = peak.second
        }
        return values
    }

    @Test
    fun expectedPositionComesFromTheSoftmaxOverCells() {
        val decoder = UfldLaneDecoder(numCells, numLanes, anchors, threshold = 0.3f, modelWidth = 800)
        val values = logits { _, lane ->
            when (lane) {
                0 -> 50 to 5f
                3 -> 150 to 5f
                else -> null
            }
        }

        val result = decoder.decode(values, 1, 2)

        assertEquals(2, result.lanes.size)
        result.lanes.forEach { assertEquals(rows, it.size) }
        assertEquals(0.256f, result.lanes[0].first().x, 0.005f)
        assertEquals(0.758f, result.lanes[1].first().x, 0.005f)
        assertEquals(121 / 288f, result.lanes[0].first().y, 0.001f)
        assertTrue(result.confidence > 0.9f)
    }

    @Test
    fun noLaneClassSuppressesThatRow() {
        val decoder = UfldLaneDecoder(numCells, numLanes, anchors, threshold = 0.3f)
        // Lane 0 only exists on the lower half of the image; the rest is explicitly "no lane".
        val values = logits { row, lane ->
            when {
                lane != 0 -> numCells to 9f
                row >= rows / 2 -> 100 to 8f
                else -> numCells to 9f
            }
        }

        val result = decoder.decode(values, 1, 2)

        assertEquals(1, result.lanes.size)
        assertEquals(rows - rows / 2, result.lanes.single().size)
        assertEquals(0.507f, result.lanes.single().first().x, 0.01f)
    }

    @Test
    fun flatLogitsAreNotMistakenForALane() {
        val decoder = UfldLaneDecoder(numCells, numLanes, anchors, threshold = 0.3f)
        val values = FloatArray((numCells + 1) * rows * numLanes)

        val result = decoder.decode(values, 1, 2)

        assertTrue(result.lanes.isEmpty())
        assertEquals(0f, result.confidence, 0f)
    }
}
