package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TensorPreprocessorTest {
    private val hash = "0".repeat(64)

    @Test
    fun writesNchwRgbTensor() {
        val contract = ModelContract(
            "test", "1", hash, TensorSpec("input", longArrayOf(1, 3, 1, 2)),
            listOf(TensorSpec("out", longArrayOf(1))), TensorLayout.NCHW, ColorOrder.RGB,
        )
        val frame = ConvertedFrame(1, 1, 2, 1, 0, byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0))
        val tensor = TensorPreprocessor.prepare(frame, contract)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), tensor.values, 0.001f)
    }

    @Test
    fun writesNhwcBgrTensor() {
        val contract = ModelContract(
            "test", "1", hash, TensorSpec("input", longArrayOf(1, 1, 1, 3)),
            listOf(TensorSpec("out", longArrayOf(1))), TensorLayout.NHWC, ColorOrder.BGR,
        )
        val frame = ConvertedFrame(1, 1, 1, 1, 0, byteArrayOf(255.toByte(), 128.toByte(), 0))
        val tensor = TensorPreprocessor.prepare(frame, contract)
        assertEquals(0f, tensor.values[0], 0.001f)
        assertEquals(128f / 255f, tensor.values[1], 0.001f)
        assertEquals(1f, tensor.values[2], 0.001f)
    }
}
