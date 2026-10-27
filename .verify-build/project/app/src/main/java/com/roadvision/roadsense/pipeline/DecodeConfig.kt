package com.roadvision.roadsense.pipeline

/** Post-processing parameters that must match the reviewed model card. */
data class DetectionDecodeConfig(
    val confidenceThreshold: Float = 0.4f,
    val nmsIouThreshold: Float = 0.5f,
    val maxDetections: Int = 100,
) {
    init {
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0f..1f) {
            "Confidence threshold must be in [0,1]: $confidenceThreshold"
        }
        require(nmsIouThreshold.isFinite() && nmsIouThreshold in 0f..1f) {
            "NMS IoU threshold must be in [0,1]: $nmsIouThreshold"
        }
        require(maxDetections in 1..1_000) { "Max detections must be in [1,1000]: $maxDetections" }
    }
}

/** How the lane head's output tensor is turned into polylines. */
enum class LaneDecodeType {
    /** One-channel probability mask of shape [1,1,H,W]. */
    MASK,

    /** Ultra-Fast-Lane-Detection grid logits of shape [1, cells+1, rows, lanes]. */
    UFLD,
}

data class LaneDecodeConfig(
    val threshold: Float = 0.5f,
    val type: LaneDecodeType = LaneDecodeType.MASK,
    /** Normalised y of each row anchor, ascending; required for [LaneDecodeType.UFLD]. */
    val rowAnchors: List<Float> = emptyList(),
) {
    init {
        require(threshold.isFinite() && threshold in 0f..1f) {
            "Lane probability threshold must be in [0,1]: $threshold"
        }
        require(rowAnchors.all { it.isFinite() && it in 0f..1f }) {
            "Row anchors must be normalised coordinates in [0,1]"
        }
        require(rowAnchors.isEmpty() || rowAnchors.size >= 2) { "Row anchors need at least two rows" }
        if (type == LaneDecodeType.UFLD) {
            require(rowAnchors.size >= 3) { "UFLD decoding needs the model's row anchors" }
        }
    }
}
