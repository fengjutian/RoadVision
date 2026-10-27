package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weight-free lane fallback: vertical edge response in the lower part of the frame, voted into a
 * (slope, intercept) accumulator, then one line per side.
 *
 * This exists so the lane path (frame throttle, coordinate mapping, overlay, diagnostics) can run
 * without a reviewed model. It is not a replacement for a learned detector: painted markings in
 * daylight are its target case, and it will lose lanes at night, in rain and through sharp curves.
 */
class ClassicalLaneDetector(
    /** Same 0..1 knob as the ONNX path: higher demands stronger edges and fewer spurious lanes. */
    private val threshold: Float = 0.5f,
    /** Fraction of the frame height where the road region starts. */
    private val roiTop: Float = 0.5f,
) : LaneDetector {
    init {
        require(threshold in 0f..1f)
        require(roiTop in 0f..0.9f)
    }

    private val slopeCount = 41
    private val slopeMin = -2.0f
    private val slopeStep = 0.1f

    override fun detect(frame: ConvertedFrame): LaneResult {
        val width = frame.width
        val height = frame.height
        val roiStart = (height * roiTop).toInt().coerceAtMost(height - 2)
        if (width < 16 || height - roiStart < 8) return emptyResult(frame)

        val edgeThreshold = 24 + (threshold * 72).roundToInt()
        val interceptStep = 2
        val interceptMin = -(2 * height)
        val interceptBins = ((width + 4 * height) / interceptStep) + 2
        val votes = IntArray(slopeCount * interceptBins)
        var edgeCount = 0

        for (y in roiStart + 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val gx = gray(frame.rgb, row + x + 1) - gray(frame.rgb, row + x - 1)
                if (abs(gx) < edgeThreshold) continue
                val gy = gray(frame.rgb, row + width + x) - gray(frame.rgb, row - width + x)
                if (abs(gx) < abs(gy)) continue
                edgeCount++
                for (slope in 0 until slopeCount) {
                    val m = slopeMin + slope * slopeStep
                    val intercept = x - m * y
                    val bin = ((intercept - interceptMin) / interceptStep).roundToInt()
                    if (bin in 0 until interceptBins) votes[slope * interceptBins + bin]++
                }
            }
        }
        if (edgeCount < 120) return emptyResult(frame)

        val minimumVotes = 18f + threshold * 30f
        val candidates = listOfNotNull(
            bestLine(votes, interceptBins, minimumVotes, leftSide = true, width, height, roiStart),
            bestLine(votes, interceptBins, minimumVotes, leftSide = false, width, height, roiStart),
        )
        val lanes = candidates.map { polyline(it, width, height, roiStart) }
        val confidence = if (candidates.isEmpty()) {
            0f
        } else {
            val best = candidates.maxOf { it.third }.toFloat()
            (best / (best + 2f * minimumVotes)).coerceIn(0f, 1f)
        }
        return LaneResult(lanes, confidence, frame.frameId, frame.timestampNs)
    }

    /** @return (slope, intercept, votes) of the strongest line on the requested side. */
    private fun bestLine(
        votes: IntArray,
        interceptBins: Int,
        minimumVotes: Float,
        leftSide: Boolean,
        width: Int,
        height: Int,
        roiStart: Int,
    ): Triple<Float, Float, Int>? {
        var bestVotes = 0
        var bestSlope = 0
        var bestBin = 0
        val slopeFrom = if (leftSide) 0 until slopeCount / 2 else slopeCount / 2 until slopeCount
        for (slope in slopeFrom) {
            for (bin in 0 until interceptBins) {
                val value = votes[slope * interceptBins + bin]
                if (value > bestVotes) {
                    bestVotes = value
                    bestSlope = slope
                    bestBin = bin
                }
            }
        }
        if (bestVotes < minimumVotes) return null
        val m = slopeMin + bestSlope * slopeStep
        val intercept = -2f * height + bestBin * 2f
        // Reject lines that only clip a corner of the road region.
        val topX = m * roiStart + intercept
        val bottomX = m * (height - 1) + intercept
        if (topX < -width * 0.2f || topX > width * 1.2f) return null
        if (bottomX < -width * 0.2f || bottomX > width * 1.2f) return null
        return Triple(m, intercept, bestVotes)
    }

    private fun polyline(
        line: Triple<Float, Float, Int>,
        width: Int,
        height: Int,
        roiStart: Int,
    ): List<LanePoint> {
        val (slope, intercept) = line
        val topX = (slope * roiStart + intercept).coerceIn(0f, width - 1f)
        val bottomX = (slope * (height - 1) + intercept).coerceIn(0f, width - 1f)
        return listOf(
            LanePoint(topX / width, roiStart.toFloat() / height),
            LanePoint(bottomX / width, (height - 1f) / height),
        )
    }

    private fun gray(rgb: ByteArray, pixel: Int): Int {
        val offset = pixel * 3
        return ((rgb[offset].toInt() and 255) * 30 + (rgb[offset + 1].toInt() and 255) * 59 +
            (rgb[offset + 2].toInt() and 255) * 11) / 100
    }

    private fun emptyResult(frame: ConvertedFrame) =
        LaneResult(emptyList(), 0f, frame.frameId, frame.timestampNs)
}
