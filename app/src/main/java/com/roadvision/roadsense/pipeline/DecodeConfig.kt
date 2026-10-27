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

data class LaneDecodeConfig(val threshold: Float = 0.5f) {
    init {
        require(threshold.isFinite() && threshold in 0f..1f) {
            "Lane probability threshold must be in [0,1]: $threshold"
        }
    }
}
