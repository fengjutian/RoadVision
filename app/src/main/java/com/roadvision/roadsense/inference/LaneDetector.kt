package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame

interface LaneDetector {
    val contract: ModelContract
    fun detect(frame: ConvertedFrame): LaneResult
}

/** Decoder for a binary lane probability map in row-major order. */
class LaneMaskDecoder(
    private val width: Int,
    private val height: Int,
    private val threshold: Float,
    private val rowStride: Int = 8,
) {
    init {
        require(width > 0 && height > 0 && threshold in 0f..1f && rowStride > 0)
    }

    fun decode(probabilities: FloatArray, frameId: Long, timestampNs: Long): LaneResult {
        require(probabilities.size == width * height)
        OutputValidator.validateFinite(probabilities, "lane output")
        val points = buildList {
            for (y in 0 until height step rowStride) {
                var bestX = -1
                var bestScore = threshold
                for (x in 0 until width) {
                    val score = probabilities[y * width + x]
                    require(score in 0f..1f) { "Lane probability is outside [0,1]" }
                    if (score >= bestScore) {
                        bestScore = score
                        bestX = x
                    }
                }
                if (bestX >= 0) add(LanePoint(bestX.toFloat() / width, y.toFloat() / height))
            }
        }
        val confidence = if (points.isEmpty()) 0f else points.map { point ->
            probabilities[(point.y * height).toInt().coerceAtMost(height - 1) * width +
                (point.x * width).toInt().coerceAtMost(width - 1)]
        }.average().toFloat()
        return LaneResult(if (points.size >= 2) listOf(points) else emptyList(), confidence, frameId, timestampNs)
    }
}
