package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import kotlin.math.floor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TensorPreprocessorTest {
    private val hash = "0".repeat(64)

    @Test
    fun matchesReferenceForEveryLayoutAndColorOrder() {
        val frame = ConvertedFrame(
            1, 1, 5, 3, 0,
            ByteArray(5 * 3 * 3) { ((it * 37 + 11) % 256).toByte() },
        )
        val mean = floatArrayOf(0.1f, 0.2f, 0.3f)
        val std = floatArrayOf(0.8f, 1.1f, 1.2f)
        for (layout in TensorLayout.entries) for (colorOrder in ColorOrder.entries) {
            val contract = ModelContract(
                "test", "1", hash, TensorSpec("input", longArrayOf(1, 3, 3, 3)),
                listOf(TensorSpec("out", longArrayOf(1))), layout, colorOrder,
                scale = 1f / 255f, mean = mean, std = std, padPixel = 114,
            )
            assertArrayEquals(
                reference(frame, contract),
                TensorPreprocessor.prepare(frame, contract).values,
                0.0001f,
            )
        }
    }

    private fun reference(frame: ConvertedFrame, contract: ModelContract): FloatArray {
        val dimensions = contract.input.shape
        val inputHeight = if (contract.layout == TensorLayout.NCHW) dimensions[2].toInt() else dimensions[1].toInt()
        val inputWidth = if (contract.layout == TensorLayout.NCHW) dimensions[3].toInt() else dimensions[2].toInt()
        val scale = minOf(inputWidth.toFloat() / frame.width, inputHeight.toFloat() / frame.height)
        val scaledWidth = frame.width * scale
        val scaledHeight = frame.height * scale
        val padX = (inputWidth - scaledWidth) / 2f
        val padY = (inputHeight - scaledHeight) / 2f
        val output = FloatArray(inputWidth * inputHeight * 3)
        for (y in 0 until inputHeight) for (x in 0 until inputWidth) {
            val sourceX = floor((x - padX) / scale).toInt()
            val sourceY = floor((y - padY) / scale).toInt()
            for (channel in 0..2) {
                val sourceChannel = if (contract.colorOrder == ColorOrder.RGB) channel else 2 - channel
                val raw = if (sourceX in 0 until frame.width && sourceY in 0 until frame.height) {
                    frame.rgb[(sourceY * frame.width + sourceX) * 3 + sourceChannel].toInt() and 255
                } else {
                    contract.padPixel
                }
                val target = if (contract.layout == TensorLayout.NCHW) {
                    channel * inputWidth * inputHeight + y * inputWidth + x
                } else {
                    (y * inputWidth + x) * 3 + channel
                }
                output[target] = (raw * contract.scale - contract.mean[channel]) / contract.std[channel]
            }
        }
        return output
    }

    @Test
    fun reusesProvidedScratchBuffer() {
        val contract = ModelContract(
            "test", "1", hash, TensorSpec("input", longArrayOf(1, 3, 1, 2)),
            listOf(TensorSpec("out", longArrayOf(1))), TensorLayout.NCHW, ColorOrder.RGB,
        )
        val frame = ConvertedFrame(1, 1, 2, 1, 0, byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0))
        val scratch = FloatArray(6)

        val tensor = TensorPreprocessor.prepare(frame, contract, scratch)

        assertSame(scratch, tensor.values)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), tensor.values, 0.001f)
    }

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

    @Test
    fun letterboxPadsWithTheContractPixelNotBlack() {
        val contract = ModelContract(
            "test", "1", hash, TensorSpec("input", longArrayOf(1, 3, 2, 2)),
            listOf(TensorSpec("out", longArrayOf(1))), TensorLayout.NCHW, ColorOrder.RGB,
            padPixel = 114,
        )
        // A 4x1 source letterboxed into a 2x2 input leaves the top row as padding.
        val frame = ConvertedFrame(1, 1, 4, 1, 0, ByteArray(12) { 255.toByte() })
        val tensor = TensorPreprocessor.prepare(frame, contract)

        assertEquals(114f / 255f, tensor.values[0], 0.001f)
        assertEquals(114f / 255f, tensor.values[1], 0.001f)
        assertEquals(1f, tensor.values[2], 0.001f)
    }

    @Test
    fun letterboxPaddingHonoursAConfiguredPixel() {
        val contract = ModelContract(
            "test", "1", hash, TensorSpec("input", longArrayOf(1, 3, 2, 2)),
            listOf(TensorSpec("out", longArrayOf(1))), TensorLayout.NCHW, ColorOrder.RGB,
            padPixel = 0,
        )
        val frame = ConvertedFrame(1, 1, 4, 1, 0, ByteArray(12) { 255.toByte() })
        val tensor = TensorPreprocessor.prepare(frame, contract)

        assertEquals(0f, tensor.values[0], 0.001f)
        assertEquals(1f, tensor.values[2], 0.001f)
    }

    @Test
    fun stretchModeFillsTheWholeInputWithoutPadding() {
        val contract = ModelContract(
            "test", "1", hash, TensorSpec("input", longArrayOf(1, 3, 2, 2)),
            listOf(TensorSpec("out", longArrayOf(1))), TensorLayout.NCHW, ColorOrder.RGB,
            resizeMode = ResizeMode.STRETCH,
        )
        // A 4x1 frame has a different aspect ratio than the 2x2 input: stretch must sample all of it.
        val frame = ConvertedFrame(1, 1, 4, 1, 0, ByteArray(12) { 255.toByte() })
        val tensor = TensorPreprocessor.prepare(frame, contract)

        for (index in 0 until 12) {
            assertEquals(1f, tensor.values[index], 0.001f)
        }
    }

    @Test
    fun stretchModeScalesSourceXIndependently() {
        val contract = ModelContract(
            "test", "1", hash, TensorSpec("input", longArrayOf(1, 3, 1, 2)),
            listOf(TensorSpec("out", longArrayOf(1))), TensorLayout.NCHW, ColorOrder.RGB,
            resizeMode = ResizeMode.STRETCH,
        )
        // Two source pixels, black then white, kept as two columns without letterbox padding.
        val frame = ConvertedFrame(1, 1, 2, 1, 0, byteArrayOf(0, 0, 0, 255.toByte(), 255.toByte(), 255.toByte()))
        val tensor = TensorPreprocessor.prepare(frame, contract)

        assertEquals(0f, tensor.values[0], 0.001f)
        assertEquals(1f, tensor.values[1], 0.001f)
    }
}
