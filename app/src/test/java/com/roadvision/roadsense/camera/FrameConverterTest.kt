package com.roadvision.roadsense.camera

import java.nio.ByteBuffer
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameConverterTest {
    private val pixels = byteArrayOf(
        1, 1, 1, 2, 2, 2,
        3, 3, 3, 4, 4, 4,
        5, 5, 5, 6, 6, 6,
    )

    @Test
    fun rotatesClockwise90() {
        assertArrayEquals(
            byteArrayOf(
                5, 5, 5, 3, 3, 3, 1, 1, 1,
                6, 6, 6, 4, 4, 4, 2, 2, 2,
            ),
            FrameConverter.rotateRgb(pixels, width = 2, height = 3, rotation = 90),
        )
    }

    @Test
    fun convertsGrayYuvAndRotatesInOnePass() {
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))
        val u = ByteBuffer.wrap(byteArrayOf(128.toByte(), 128.toByte()))
        val v = ByteBuffer.wrap(byteArrayOf(128.toByte(), 128.toByte()))
        assertArrayEquals(
            byteArrayOf(
                5, 5, 5, 3, 3, 3, 1, 1, 1,
                6, 6, 6, 4, 4, 4, 2, 2, 2,
            ),
            FrameConverter.yuvToRgbRotated(
                yBuffer = y, yRowStride = 2, yPixelStride = 1,
                uBuffer = u, uRowStride = 1, uPixelStride = 1,
                vBuffer = v, vRowStride = 1, vPixelStride = 1,
                width = 2, height = 3, rotation = 90,
            ),
        )
    }

    @Test
    fun fusedConversionMatchesFloatReferenceForEveryRotation() {
        val width = 4
        val height = 4
        val yBytes = ByteArray(width * height) { ((it * 13 + 7) % 256).toByte() }
        val uBytes = ByteArray(8) { if (it % 2 == 0) ((it * 29 + 50) % 256).toByte() else 0 }
        val vBytes = ByteArray(8) { if (it % 2 == 0) ((it * 17 + 190) % 256).toByte() else 0 }
        for (rotation in listOf(0, 90, 180, 270)) {
            val actual = FrameConverter.yuvToRgbRotated(
                yBuffer = ByteBuffer.wrap(yBytes), yRowStride = width, yPixelStride = 1,
                uBuffer = ByteBuffer.wrap(uBytes), uRowStride = 4, uPixelStride = 2,
                vBuffer = ByteBuffer.wrap(vBytes), vRowStride = 4, vPixelStride = 2,
                width = width, height = height, rotation = rotation,
            )
            val expected = floatReference(yBytes, uBytes, vBytes, width, height, rotation)
            for (index in expected.indices) {
                assertTrue(
                    "rotation=$rotation index=$index expected=${expected[index]} actual=${actual[index]}",
                    abs((expected[index].toInt() and 0xff) - (actual[index].toInt() and 0xff)) <= 2,
                )
            }
        }
    }

    private fun floatReference(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        width: Int,
        height: Int,
        rotation: Int,
    ): ByteArray {
        val unrotated = ByteArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val yValue = yBytes[y * width + x].toInt() and 0xff
            val uValue = (uBytes[(y / 2) * 4 + (x / 2) * 2].toInt() and 0xff) - 128
            val vValue = (vBytes[(y / 2) * 4 + (x / 2) * 2].toInt() and 0xff) - 128
            val offset = (y * width + x) * 3
            unrotated[offset] = clamp(yValue + 1.402f * vValue)
            unrotated[offset + 1] = clamp(yValue - 0.344136f * uValue - 0.714136f * vValue)
            unrotated[offset + 2] = clamp(yValue + 1.772f * uValue)
        }
        return FrameConverter.rotateRgb(unrotated, width, height, rotation)
    }

    private fun clamp(value: Float): Byte = value.toInt().coerceIn(0, 255).toByte()

    @Test
    fun rotatesClockwise180() {
        assertArrayEquals(
            byteArrayOf(
                6, 6, 6, 5, 5, 5,
                4, 4, 4, 3, 3, 3,
                2, 2, 2, 1, 1, 1,
            ),
            FrameConverter.rotateRgb(pixels, width = 2, height = 3, rotation = 180),
        )
    }
}
