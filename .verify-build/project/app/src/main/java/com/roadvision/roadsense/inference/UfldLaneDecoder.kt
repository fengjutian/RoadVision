package com.roadvision.roadsense.inference

import kotlin.math.exp

/**
 * Decoder for Ultra-Fast-Lane-Detection grid logits shaped [1, cells + 1, rows, lanes].
 *
 * The extra class after the [numCells] position bins means "no lane on this row". Positions are the
 * expectation over the position bins, exactly as in the reference demo: softmax over the position
 * bins only, weighted by `index + 1`, then scaled by the column width of the model input. Output
 * coordinates are normalised to the model input, which for a stretched resize is the frame itself.
 */
class UfldLaneDecoder(
    private val numCells: Int,
    private val numLanes: Int,
    private val rowAnchors: FloatArray,
    private val threshold: Float = 0.3f,
    private val minimumPoints: Int = 3,
    private val modelWidth: Int = 800,
) : LaneDecoder {
    init {
        require(numCells >= 2) { "UFLD needs at least two position bins" }
        require(numLanes in 1..8) { "Unsupported lane count: $numLanes" }
        require(rowAnchors.size >= 3) { "UFLD needs the model's row anchors" }
        require(modelWidth >= 2)
        require(threshold in 0f..1f)
        require(minimumPoints >= 2)
    }

    private val rows = rowAnchors.size
    private val columnWidth = (modelWidth - 1f) / (numCells - 1) / modelWidth

    override fun decode(values: FloatArray, frameId: Long, timestampNs: Long): LaneResult {
        val classes = numCells + 1
        require(values.size == classes * rows * numLanes) {
            "UFLD output has ${values.size} values, expected ${classes * rows * numLanes}"
        }
        val lanes = mutableListOf<List<LanePoint>>()
        var confidenceSum = 0f
        var kept = 0

        for (lane in 0 until numLanes) {
            val points = mutableListOf<LanePoint>()
            var scoreSum = 0f
            for (row in 0 until rows) {
                val base = row * numLanes + lane
                val stride = rows * numLanes
                var argmax = 0
                var best = Float.NEGATIVE_INFINITY
                for (cell in 0 until classes) {
                    val value = values[cell * stride + base]
                    if (value > best) {
                        best = value
                        argmax = cell
                    }
                }
                if (argmax == numCells) continue

                var maxCell = Float.NEGATIVE_INFINITY
                for (cell in 0 until numCells) {
                    val value = values[cell * stride + base]
                    if (value > maxCell) maxCell = value
                }
                var total = 0.0
                var expected = 0.0
                for (cell in 0 until numCells) {
                    val probability = exp((values[cell * stride + base] - maxCell).toDouble())
                    total += probability
                    expected += (cell + 1) * probability
                }
                if (total <= 0.0) continue
                val peakProbability = (1.0 / total).toFloat()
                if (peakProbability < threshold) continue
                val x = (expected / total * columnWidth).toFloat().coerceIn(0f, 1f)
                points += LanePoint(x, rowAnchors[row])
                scoreSum += peakProbability
            }
            if (points.size >= minimumPoints) {
                lanes += points
                confidenceSum += scoreSum / points.size
                kept++
            }
        }
        val confidence = if (kept == 0) 0f else confidenceSum / kept
        return LaneResult(lanes, confidence, frameId, timestampNs)
    }
}
