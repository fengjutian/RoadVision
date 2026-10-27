package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class ClassicalLaneDetectorTest {
    private val width = 320
    private val height = 160

    private fun frame(background: Int = 20, lines: List<Pair<Pair<Int, Int>, Pair<Int, Int>>> = emptyList()): ConvertedFrame {
        val rgb = ByteArray(width * height * 3) { background.toByte() }
        lines.forEach { (start, end) -> draw(rgb, start, end) }
        return ConvertedFrame(1, 1, width, height, 0, rgb)
    }

    private fun draw(rgb: ByteArray, start: Pair<Int, Int>, end: Pair<Int, Int>) {
        val steps = maxOf(kotlin.math.abs(end.first - start.first), kotlin.math.abs(end.second - start.second))
        for (step in 0..steps) {
            val x = (start.first + (end.first - start.first) * step.toFloat() / steps).roundToInt()
            val y = (start.second + (end.second - start.second) * step.toFloat() / steps).roundToInt()
            for (dx in -1..1) {
                val px = (x + dx).coerceIn(0, width - 1)
                val offset = (y * width + px) * 3
                if (offset + 2 < rgb.size) {
                    rgb[offset] = 255.toByte()
                    rgb[offset + 1] = 255.toByte()
                    rgb[offset + 2] = 255.toByte()
                }
            }
        }
    }

    @Test
    fun findsBothMarkedLanes() {
        val detector = ClassicalLaneDetector(threshold = 0.5f)
        val result = detector.detect(frame(lines = listOf((56 to 159) to (120 to 80), (264 to 159) to (200 to 80))))

        assertEquals(2, result.lanes.size)
        val bottoms = result.lanes.map { it.last().x }
        assertTrue("left lane near 0.175 but was $bottoms", bottoms[0] in 0.05f..0.35f)
        assertTrue("right lane near 0.825 but was $bottoms", bottoms[1] in 0.65f..0.95f)
        assertTrue(result.confidence > 0f)
    }

    @Test
    fun blankRoadReportsNoLanes() {
        val detector = ClassicalLaneDetector(threshold = 0.5f)
        val result = detector.detect(frame())

        assertTrue(result.lanes.isEmpty())
        assertEquals(0f, result.confidence, 0f)
    }

    @Test
    fun aSingleMarkingDoesNotInventTheOtherSide() {
        val detector = ClassicalLaneDetector(threshold = 0.5f)
        val result = detector.detect(frame(lines = listOf((56 to 159) to (120 to 80))))

        assertEquals(1, result.lanes.size)
        assertTrue(result.lanes.single().last().x in 0.05f..0.35f)
    }

    @Test
    fun horizontallyStripedTextureIsNotALane() {
        // A ladder-like pattern has strong vertical edges but no consistent slope per side.
        val rgb = ByteArray(width * height * 3) { 20.toByte() }
        for (y in 80 until height step 6) {
            for (x in 0 until width) {
                val offset = (y * width + x) * 3
                rgb[offset] = 255.toByte(); rgb[offset + 1] = 255.toByte(); rgb[offset + 2] = 255.toByte()
            }
        }
        val detector = ClassicalLaneDetector(threshold = 0.9f)
        val result = detector.detect(ConvertedFrame(1, 1, width, height, 0, rgb))

        // Rungs are horizontal, so the vertical-gradient test should reject nearly every pixel.
        assertTrue(result.lanes.isEmpty())
    }
}
