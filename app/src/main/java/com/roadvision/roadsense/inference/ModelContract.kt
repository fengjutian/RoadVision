package com.roadvision.roadsense.inference

import kotlin.math.max
import kotlin.math.min

enum class TensorLayout { NCHW, NHWC }
enum class TensorDataType { FLOAT32 }
enum class ColorOrder { RGB, BGR }

data class TensorSpec(
    val name: String,
    val shape: LongArray,
    val dataType: TensorDataType = TensorDataType.FLOAT32,
)

data class ModelContract(
    val id: String,
    val version: String,
    val sha256: String,
    val input: TensorSpec,
    val outputs: List<TensorSpec>,
    val layout: TensorLayout,
    val colorOrder: ColorOrder,
    val scale: Float = 1f / 255f,
    val mean: FloatArray = floatArrayOf(0f, 0f, 0f),
    val std: FloatArray = floatArrayOf(1f, 1f, 1f),
    /**
     * Raw pixel value used for letterbox padding, before [scale]/[mean]/[std]. YOLO-family models
     * are trained with grey 114 padding; black 0 padding shifts the border region and hurts recall
     * near the top and bottom of a 4:3 frame.
     */
    val padPixel: Int = 114,
) {
    init {
        require(id.isNotBlank() && version.isNotBlank())
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Model SHA-256 is required" }
        require(input.shape.size == 4) { "Image input must be rank 4" }
        require(mean.size == 3 && std.size == 3 && std.all { it > 0f })
        require(padPixel in 0..255) { "Letterbox pad pixel must be a byte value" }
        require(outputs.isNotEmpty())
    }
}

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left <= right && top <= bottom)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)

    fun clamp() = NormalizedRect(
        left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f),
    )

    fun iou(other: NormalizedRect): Float {
        val intersection = max(0f, min(right, other.right) - max(left, other.left)) *
            max(0f, min(bottom, other.bottom) - max(top, other.top))
        val union = area + other.area - intersection
        return if (union > 0f) intersection / union else 0f
    }
}

data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val box: NormalizedRect,
    val frameId: Long,
    val timestampNs: Long,
)

data class LanePoint(val x: Float, val y: Float)
data class LaneResult(
    val lanes: List<List<LanePoint>>,
    val confidence: Float,
    val frameId: Long,
    val timestampNs: Long,
)
