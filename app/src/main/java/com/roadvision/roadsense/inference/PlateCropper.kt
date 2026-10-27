package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import kotlin.math.roundToInt

data class ImagePoint(val x: Float, val y: Float)
data class PlateQuad(val topLeft: ImagePoint, val topRight: ImagePoint, val bottomRight: ImagePoint, val bottomLeft: ImagePoint)
data class PlateCrop(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val sharpness: Double,
    val acceptable: Boolean,
    val rejectionReason: String? = null,
)

/** Extracts and rectifies a four-corner plate candidate without retaining a camera frame. */
class PlateCropper(
    private val outputWidth: Int = 160,
    private val outputHeight: Int = 48,
    private val minimumSourceWidth: Int = 48,
    private val minimumSharpness: Double = 55.0,
) {
    fun crop(frame: ConvertedFrame, quad: PlateQuad): PlateCrop {
        val sourceWidth = distance(quad.topLeft, quad.topRight) * frame.width
        if (sourceWidth < minimumSourceWidth) return rejected("plate_too_small")
        val output = ByteArray(outputWidth * outputHeight * 3)
        for (y in 0 until outputHeight) {
            val v = y.toFloat() / (outputHeight - 1).coerceAtLeast(1)
            for (x in 0 until outputWidth) {
                val u = x.toFloat() / (outputWidth - 1).coerceAtLeast(1)
                val source = bilinear(quad, u, v)
                sampleRgb(frame, source.x, source.y, output, (y * outputWidth + x) * 3)
            }
        }
        val sharpness = laplacianVariance(output, outputWidth, outputHeight)
        return PlateCrop(
            output, outputWidth, outputHeight, sharpness,
            acceptable = sharpness >= minimumSharpness,
            rejectionReason = if (sharpness < minimumSharpness) "plate_blurry" else null,
        )
    }

    private fun rejected(reason: String) = PlateCrop(ByteArray(0), 0, 0, 0.0, false, reason)

    private fun bilinear(q: PlateQuad, u: Float, v: Float): ImagePoint {
        val top = lerp(q.topLeft, q.topRight, u)
        val bottom = lerp(q.bottomLeft, q.bottomRight, u)
        return lerp(top, bottom, v)
    }

    private fun lerp(a: ImagePoint, b: ImagePoint, ratio: Float) =
        ImagePoint(a.x + (b.x - a.x) * ratio, a.y + (b.y - a.y) * ratio)

    private fun distance(a: ImagePoint, b: ImagePoint): Float =
        kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    private fun sampleRgb(frame: ConvertedFrame, normalizedX: Float, normalizedY: Float, output: ByteArray, offset: Int) {
        val x = (normalizedX.coerceIn(0f, 1f) * (frame.width - 1)).roundToInt()
        val y = (normalizedY.coerceIn(0f, 1f) * (frame.height - 1)).roundToInt()
        val sourceOffset = (y * frame.width + x) * 3
        frame.rgb.copyInto(output, offset, sourceOffset, sourceOffset + 3)
    }

    private fun laplacianVariance(rgb: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var squared = 0.0
        var count = 0
        fun gray(x: Int, y: Int): Int {
            val i = (y * width + x) * 3
            return ((rgb[i].toInt() and 255) * 30 + (rgb[i + 1].toInt() and 255) * 59 +
                (rgb[i + 2].toInt() and 255) * 11) / 100
        }
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val value = 4 * gray(x, y) - gray(x - 1, y) - gray(x + 1, y) - gray(x, y - 1) - gray(x, y + 1)
            sum += value
            squared += value.toDouble() * value
            count++
        }
        val mean = sum / count
        return squared / count - mean * mean
    }
}
