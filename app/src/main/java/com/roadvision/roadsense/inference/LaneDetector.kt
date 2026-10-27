package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import kotlin.math.abs

interface LaneDetector {
    fun detect(frame: ConvertedFrame): LaneResult
}

/** Turns one lane head's flattened output into polylines. */
interface LaneDecoder {
    fun decode(values: FloatArray, frameId: Long, timestampNs: Long): LaneResult
}

/**
 * Decoder for a binary lane probability map in row-major order.
 *
 * Each sampled row is reduced to a few local maxima above [threshold] (non-maximum suppression along
 * the row), and those peaks are linked into up to [maxLanes] polylines by nearest-x continuity. A
 * single-lane model therefore still decodes to one polyline, while a mask that highlights the left
 * and right markings yields two, rather than an average of both.
 */
class LaneMaskDecoder(
    private val width: Int,
    private val height: Int,
    private val threshold: Float,
    private val rowStride: Int = 8,
    private val maxLanes: Int = 3,
    private val minimumPoints: Int = 3,
) : LaneDecoder {
    init {
        require(width > 0 && height > 0 && threshold in 0f..1f && rowStride > 0)
        require(maxLanes in 1..8)
        require(minimumPoints >= 2) { "A lane needs at least two points to be drawn" }
    }

    private val peakGapPx = (width / 32).coerceAtLeast(2)
    private val linkStepPx = (width / 8).coerceAtLeast(4)

    private class Trace {
        val points = mutableListOf<LanePoint>()
        var lastX = 0f
        var lastRow = -1
        var scoreSum = 0f
    }

    override fun decode(probabilities: FloatArray, frameId: Long, timestampNs: Long): LaneResult {
        require(probabilities.size == width * height)
        OutputValidator.validateFinite(probabilities, "lane output")
        for (score in probabilities) {
            require(score in 0f..1f) { "Lane probability is outside [0,1]" }
        }

        val traces = mutableListOf<Trace>()
        for (row in 0 until height step rowStride) {
            val peaks = peaksInRow(probabilities, row)
            if (peaks.isEmpty()) continue
            for ((x, score) in peaks) {
                val trace = nearestTrace(traces, x, row) ?: if (traces.size < maxLanes) {
                    Trace().also { traces += it }
                } else {
                    continue
                }
                trace.points += LanePoint(x / width.toFloat(), row / height.toFloat())
                trace.lastX = x
                trace.lastRow = row
                trace.scoreSum += score
            }
        }

        val lanes = traces.filter { it.points.size >= minimumPoints }
        val confidence = if (lanes.isEmpty()) {
            0f
        } else {
            lanes.sumOf { (it.scoreSum / it.points.size).toDouble() }.div(lanes.size).toFloat()
        }
        return LaneResult(lanes.map { it.points }, confidence, frameId, timestampNs)
    }

    private fun nearestTrace(traces: List<Trace>, x: Float, row: Int): Trace? {
        var best: Trace? = null
        var bestDistance = Float.MAX_VALUE
        for (trace in traces) {
            if (trace.lastRow == row) continue
            val distance = abs(trace.lastX - x)
            if (distance <= linkStepPx && distance < bestDistance) {
                best = trace
                bestDistance = distance
            }
        }
        return best
    }

    /** Row-local maxima above [threshold], separated by at least [peakGapPx] pixels. */
    private fun peaksInRow(probabilities: FloatArray, row: Int): List<Pair<Float, Float>> {
        val offset = row * width
        val peaks = ArrayList<Pair<Float, Float>>()
        var x = 0
        while (x < width && peaks.size < maxLanes) {
            if (probabilities[offset + x] < threshold) {
                x++
                continue
            }
            var bestX = x
            var bestScore = probabilities[offset + x]
            val end = minOf(x + peakGapPx - 1, width - 1)
            for (index in x..end) {
                val score = probabilities[offset + index]
                if (score > bestScore) {
                    bestScore = score
                    bestX = index
                }
            }
            peaks += bestX.toFloat() to bestScore
            x = end + 1
        }
        return peaks
    }
}
