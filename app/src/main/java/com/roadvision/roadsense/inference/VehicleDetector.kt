package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame

interface VehicleDetector {
    val contract: ModelContract
    fun detect(frame: ConvertedFrame): List<Detection>
}

/** Decoder for reviewed models exported as [1, N, 6]: x1,y1,x2,y2,score,classId. */
class VehicleOutputDecoder(
    private val labels: Map<Int, String>,
    private val confidenceThreshold: Float,
    private val nmsIouThreshold: Float,
) {
    init {
        require(confidenceThreshold in 0f..1f && nmsIouThreshold in 0f..1f)
    }

    fun decode(rows: Array<FloatArray>, frameId: Long, timestampNs: Long): List<Detection> {
        val candidates = rows.mapNotNull { row ->
            require(row.size == 6) { "Vehicle output row must contain 6 values" }
            OutputValidator.validateFinite(row, "vehicle output")
            val score = OutputValidator.confidence(row[4])
            if (score < confidenceThreshold) return@mapNotNull null
            val classId = row[5].toInt()
            require(row[5] == classId.toFloat()) { "Class ID must be an integer" }
            Detection(
                classId = classId,
                label = labels[classId] ?: "class-$classId",
                confidence = score,
                box = OutputValidator.normalizedBox(row[0], row[1], row[2], row[3]),
                frameId = frameId,
                timestampNs = timestampNs,
            )
        }.sortedByDescending(Detection::confidence)

        val selected = mutableListOf<Detection>()
        candidates.forEach { candidate ->
            if (selected.none { it.classId == candidate.classId && it.box.iou(candidate.box) > nmsIouThreshold }) {
                selected += candidate
            }
        }
        return selected
    }
}
