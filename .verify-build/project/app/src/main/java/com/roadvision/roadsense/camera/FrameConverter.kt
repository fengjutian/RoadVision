package com.roadvision.roadsense.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

data class ConvertedFrame(
    val frameId: Long,
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    /** Packed RGB bytes in display orientation. */
    val rgb: ByteArray,
    /**
     * `System.nanoTime()` at the moment CameraX handed the frame to our analyzer. Unlike
     * [timestampNs] (the camera sensor timestamp, whose clock base is platform specific) this value
     * is guaranteed to share the clock of every other in-process measurement, so it is what the
     * end-to-end latency is derived from.
     */
    val ingressNs: Long = timestampNs,
)

/** Converts YUV_420_888 frames without retaining CameraX-owned buffers. */
object FrameConverter {
    /**
     * @param ingressNs the analyzer ingress timestamp to carry on the frame; defaults to the clock
     *   reading taken before the conversion work starts, so conversion time is included in latency.
     */
    fun convert(image: ImageProxy, frameId: Long, ingressNs: Long = System.nanoTime()): ConvertedFrame {
        require(image.planes.size == 3) { "Expected YUV_420_888 with three planes" }
        val sourceWidth = image.width
        val sourceHeight = image.height
        val rotation = normalizeRotation(image.imageInfo.rotationDegrees)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val rgb = yuvToRgbRotated(
            yBuffer = yPlane.buffer,
            yRowStride = yPlane.rowStride,
            yPixelStride = yPlane.pixelStride,
            uBuffer = uPlane.buffer,
            uRowStride = uPlane.rowStride,
            uPixelStride = uPlane.pixelStride,
            vBuffer = vPlane.buffer,
            vRowStride = vPlane.rowStride,
            vPixelStride = vPlane.pixelStride,
            width = sourceWidth,
            height = sourceHeight,
            rotation = rotation,
        )
        val swapsDimensions = rotation == 90 || rotation == 270
        return ConvertedFrame(
            frameId = frameId,
            timestampNs = image.imageInfo.timestamp,
            width = if (swapsDimensions) sourceHeight else sourceWidth,
            height = if (swapsDimensions) sourceWidth else sourceHeight,
            rotationDegrees = rotation,
            rgb = rgb,
            ingressNs = ingressNs,
        )
    }

    /**
     * Converts and rotates in one pass, writing directly into the display-oriented buffer. This
     * avoids the full-size intermediate buffer plus a second copy that the previous implementation
     * used. YUV is sampled in 2x2 blocks so each chroma pair is read once.
     */
    internal fun yuvToRgbRotated(
        yBuffer: ByteBuffer,
        yRowStride: Int,
        yPixelStride: Int,
        uBuffer: ByteBuffer,
        uRowStride: Int,
        uPixelStride: Int,
        vBuffer: ByteBuffer,
        vRowStride: Int,
        vPixelStride: Int,
        width: Int,
        height: Int,
        rotation: Int,
    ): ByteArray {
        val normalizedRotation = normalizeRotation(rotation)
        val swapsDimensions = normalizedRotation == 90 || normalizedRotation == 270
        val outputWidth = if (swapsDimensions) height else width
        val outputHeight = if (swapsDimensions) width else height
        val output = ByteArray(outputWidth * outputHeight * 3)

        fun writePixel(x: Int, y: Int, yValue: Int, u: Int, v: Int) {
            val r = (yValue + ((1_436 * v) shr 10)).coerceIn(0, 255)
            val g = (yValue - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
            val b = (yValue + ((1_815 * u) shr 10)).coerceIn(0, 255)
            val targetX: Int
            val targetY: Int
            when (normalizedRotation) {
                0 -> {
                    targetX = x
                    targetY = y
                }
                90 -> {
                    targetX = height - 1 - y
                    targetY = x
                }
                180 -> {
                    targetX = width - 1 - x
                    targetY = height - 1 - y
                }
                else -> {
                    targetX = y
                    targetY = width - 1 - x
                }
            }
            val target = (targetY * outputWidth + targetX) * 3
            output[target] = r.toByte()
            output[target + 1] = g.toByte()
            output[target + 2] = b.toByte()
        }

        var y = 0
        while (y < height) {
            val yRow = y * yRowStride
            val chromaRow = (y / 2).toLong()
            val uRow = (chromaRow * uRowStride).toInt()
            val vRow = (chromaRow * vRowStride).toInt()
            var x = 0
            while (x < width) {
                val chromaColumn = x / 2
                val u = (uBuffer.get(uRow + chromaColumn * uPixelStride).toInt() and 0xff) - 128
                val v = (vBuffer.get(vRow + chromaColumn * vPixelStride).toInt() and 0xff) - 128
                val yValue = yBuffer.get(yRow + x * yPixelStride).toInt() and 0xff
                writePixel(x, y, yValue, u, v)
                if (x + 1 < width) {
                    val yNext = yBuffer.get(yRow + (x + 1) * yPixelStride).toInt() and 0xff
                    writePixel(x + 1, y, yNext, u, v)
                }
                if (y + 1 < height) {
                    val yNextRow = (y + 1) * yRowStride
                    val yBelow = yBuffer.get(yNextRow + x * yPixelStride).toInt() and 0xff
                    writePixel(x, y + 1, yBelow, u, v)
                    if (x + 1 < width) {
                        val yBelowNext = yBuffer.get(yNextRow + (x + 1) * yPixelStride).toInt() and 0xff
                        writePixel(x + 1, y + 1, yBelowNext, u, v)
                    }
                }
                x += 2
            }
            y += 2
        }
        return output
    }

    private fun normalizeRotation(rotation: Int): Int {
        val normalized = ((rotation % 360) + 360) % 360
        require(normalized % 90 == 0) { "Unsupported rotation: $rotation" }
        return normalized
    }

    internal fun rotateRgb(input: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
        if (rotation == 0) return input
        val output = ByteArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (targetX, targetY, targetWidth) = when (rotation) {
                    90 -> Triple(height - 1 - y, x, height)
                    180 -> Triple(width - 1 - x, height - 1 - y, width)
                    270 -> Triple(y, width - 1 - x, height)
                    else -> error("Rotation must be 0, 90, 180, or 270")
                }
                val sourceOffset = (y * width + x) * 3
                val targetOffset = (targetY * targetWidth + targetX) * 3
                input.copyInto(output, targetOffset, sourceOffset, sourceOffset + 3)
            }
        }
        return output
    }
}
