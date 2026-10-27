package com.roadvision.roadsense.camera

import androidx.camera.core.ImageProxy

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
        val rotation = image.imageInfo.rotationDegrees
        val unrotated = ByteArray(sourceWidth * sourceHeight * 3)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        for (y in 0 until sourceHeight) {
            for (x in 0 until sourceWidth) {
                val yValue = sample(yPlane, x, y)
                val uValue = sample(uPlane, x / 2, y / 2) - 128
                val vValue = sample(vPlane, x / 2, y / 2) - 128
                val output = (y * sourceWidth + x) * 3
                unrotated[output] = clamp(yValue + 1.402f * vValue)
                unrotated[output + 1] = clamp(yValue - 0.344136f * uValue - 0.714136f * vValue)
                unrotated[output + 2] = clamp(yValue + 1.772f * uValue)
            }
        }

        val normalizedRotation = ((rotation % 360) + 360) % 360
        require(normalizedRotation % 90 == 0) { "Unsupported rotation: $rotation" }
        val rotated = rotateRgb(unrotated, sourceWidth, sourceHeight, normalizedRotation)
        val swapsDimensions = normalizedRotation == 90 || normalizedRotation == 270
        return ConvertedFrame(
            frameId = frameId,
            timestampNs = image.imageInfo.timestamp,
            width = if (swapsDimensions) sourceHeight else sourceWidth,
            height = if (swapsDimensions) sourceWidth else sourceHeight,
            rotationDegrees = normalizedRotation,
            rgb = rotated,
            ingressNs = ingressNs,
        )
    }

    private fun sample(plane: ImageProxy.PlaneProxy, x: Int, y: Int): Int {
        val index = y * plane.rowStride + x * plane.pixelStride
        return plane.buffer.get(index).toInt() and 0xff
    }

    private fun clamp(value: Float): Byte = value.toInt().coerceIn(0, 255).toByte()

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
