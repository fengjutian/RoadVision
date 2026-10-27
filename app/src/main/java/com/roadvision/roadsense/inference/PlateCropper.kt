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
                val source = project(quad, u, v)
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

    /** Projective mapping from a unit rectangle to the supplied source quadrilateral. */
    internal fun project(q: PlateQuad, u: Float, v: Float): ImagePoint {
        val x0 = q.topLeft.x
        val y0 = q.topLeft.y
        val x1 = q.topRight.x
        val y1 = q.topRight.y
        val x2 = q.bottomRight.x
        val y2 = q.bottomRight.y
        val x3 = q.bottomLeft.x
        val y3 = q.bottomLeft.y
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        val determinant = dx1 * dy2 - dx2 * dy1
        val g = if (kotlin.math.abs(determinant) < 1e-8f) 0f else (sx * dy2 - dx2 * sy) / determinant
        val h = if (kotlin.math.abs(determinant) < 1e-8f) 0f else (dx1 * sy - sx * dy1) / determinant
        val a = x1 - x0 + g * x1
        val b = x3 - x0 + h * x3
        val d = y1 - y0 + g * y1
        val e = y3 - y0 + h * y3
        val denominator = g * u + h * v + 1f
        require(kotlin.math.abs(denominator) > 1e-8f) { "Degenerate plate quadrilateral" }
        return ImagePoint(
            (a * u + b * v + x0) / denominator,
            (d * u + e * v + y0) / denominator,
        )
    }

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
