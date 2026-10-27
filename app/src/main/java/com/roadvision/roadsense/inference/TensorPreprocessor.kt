package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame
import kotlin.math.floor

data class PreparedTensor(val values: FloatArray, val shape: LongArray, val transform: FrameTransform)

object TensorPreprocessor {
    /**
     * @param reuse optional buffer from the previous frame. Detectors own one scratch buffer and
     *   pass it back here to avoid allocating a multi-megabyte FloatArray per frame. The returned
     *   array is only valid until the next [prepare] call on the same detector.
     */
    fun prepare(frame: ConvertedFrame, contract: ModelContract, reuse: FloatArray? = null): PreparedTensor {
        val dimensions = contract.input.shape
        val inputHeight = when (contract.layout) {
            TensorLayout.NCHW -> dimensions[2].toInt()
            TensorLayout.NHWC -> dimensions[1].toInt()
        }
        val inputWidth = when (contract.layout) {
            TensorLayout.NCHW -> dimensions[3].toInt()
            TensorLayout.NHWC -> dimensions[2].toInt()
        }
        require(inputWidth > 0 && inputHeight > 0 && dimensions[0] == 1L)
        require(frame.rgb.size == frame.width * frame.height * 3) { "RGB buffer does not match frame size" }

        val transform: FrameTransform
        val required = inputWidth * inputHeight * 3
        val output = if (reuse != null && reuse.size == required) reuse else FloatArray(required)
        val xMap: IntArray
        val yMap: IntArray
        when (contract.resizeMode) {
            ResizeMode.LETTERBOX -> {
                transform = LetterboxTransform(frame.width, frame.height, inputWidth, inputHeight)
                val scale = minOf(inputWidth.toFloat() / frame.width, inputHeight.toFloat() / frame.height)
                val scaledWidth = frame.width * scale
                val scaledHeight = frame.height * scale
                val padX = (inputWidth - scaledWidth) / 2f
                val padY = (inputHeight - scaledHeight) / 2f
                // The letterbox mapping is identical for every row/column. Precompute it once
                // instead of dividing per pixel, and keep mean/std in gain+bias form.
                xMap = IntArray(inputWidth) { x ->
                    val sourceX = floor((x - padX) / scale).toInt()
                    if (sourceX in 0 until frame.width) sourceX else -1
                }
                yMap = IntArray(inputHeight) { y ->
                    val sourceY = floor((y - padY) / scale).toInt()
                    if (sourceY in 0 until frame.height) sourceY else -1
                }
            }

            ResizeMode.STRETCH -> {
                transform = StretchTransform(frame.width, frame.height, inputWidth, inputHeight)
                xMap = IntArray(inputWidth) { x -> (x * frame.width / inputWidth).coerceIn(0, frame.width - 1) }
                yMap = IntArray(inputHeight) { y -> (y * frame.height / inputHeight).coerceIn(0, frame.height - 1) }
            }
        }
        val gain = FloatArray(3) { contract.scale / contract.std[it] }
        val bias = FloatArray(3) { -contract.mean[it] / contract.std[it] }
        val pad = FloatArray(3) { contract.padPixel * gain[it] + bias[it] }

        when (contract.layout) {
            TensorLayout.NCHW -> writeNchw(
                frame, contract.colorOrder, output, inputWidth, inputHeight, xMap, yMap, gain, bias, pad,
            )
            TensorLayout.NHWC -> writeNhwc(
                frame, contract.colorOrder, output, inputWidth, inputHeight, xMap, yMap, gain, bias, pad,
            )
        }
        return PreparedTensor(output, dimensions, transform)
    }

    private fun writeNchw(
        frame: ConvertedFrame,
        colorOrder: ColorOrder,
        output: FloatArray,
        inputWidth: Int,
        inputHeight: Int,
        xMap: IntArray,
        yMap: IntArray,
        gain: FloatArray,
        bias: FloatArray,
        pad: FloatArray,
    ) {
        val rgb = frame.rgb
        val sourceWidth = frame.width
        val planeSize = inputWidth * inputHeight
        // Output channel 0 maps to R for RGB and B for BGR; channel 2 is the opposite. Green is +1.
        val firstChannel = if (colorOrder == ColorOrder.RGB) 0 else 2
        val thirdChannel = if (colorOrder == ColorOrder.RGB) 2 else 0
        for (y in 0 until inputHeight) {
            val rowOffset = y * inputWidth
            val sourceY = yMap[y]
            if (sourceY < 0) {
                for (x in 0 until inputWidth) {
                    val index = rowOffset + x
                    output[index] = pad[0]
                    output[planeSize + index] = pad[1]
                    output[2 * planeSize + index] = pad[2]
                }
                continue
            }
            val sourceRow = sourceY * sourceWidth * 3
            for (x in 0 until inputWidth) {
                val index = rowOffset + x
                val sourceX = xMap[x]
                if (sourceX < 0) {
                    output[index] = pad[0]
                    output[planeSize + index] = pad[1]
                    output[2 * planeSize + index] = pad[2]
                    continue
                }
                val base = sourceRow + sourceX * 3
                output[index] = (rgb[base + firstChannel].toInt() and 0xff) * gain[0] + bias[0]
                output[planeSize + index] = (rgb[base + 1].toInt() and 0xff) * gain[1] + bias[1]
                output[2 * planeSize + index] = (rgb[base + thirdChannel].toInt() and 0xff) * gain[2] + bias[2]
            }
        }
    }

    private fun writeNhwc(
        frame: ConvertedFrame,
        colorOrder: ColorOrder,
        output: FloatArray,
        inputWidth: Int,
        inputHeight: Int,
        xMap: IntArray,
        yMap: IntArray,
        gain: FloatArray,
        bias: FloatArray,
        pad: FloatArray,
    ) {
        val rgb = frame.rgb
        val sourceWidth = frame.width
        val firstChannel = if (colorOrder == ColorOrder.RGB) 0 else 2
        val thirdChannel = if (colorOrder == ColorOrder.RGB) 2 else 0
        for (y in 0 until inputHeight) {
            val sourceY = yMap[y]
            if (sourceY < 0) {
                for (x in 0 until inputWidth) {
                    val index = (y * inputWidth + x) * 3
                    output[index] = pad[0]
                    output[index + 1] = pad[1]
                    output[index + 2] = pad[2]
                }
                continue
            }
            val sourceRow = sourceY * sourceWidth * 3
            for (x in 0 until inputWidth) {
                val target = (y * inputWidth + x) * 3
                val sourceX = xMap[x]
                if (sourceX < 0) {
                    output[target] = pad[0]
                    output[target + 1] = pad[1]
                    output[target + 2] = pad[2]
                    continue
                }
                val base = sourceRow + sourceX * 3
                output[target] = (rgb[base + firstChannel].toInt() and 0xff) * gain[0] + bias[0]
                output[target + 1] = (rgb[base + 1].toInt() and 0xff) * gain[1] + bias[1]
                output[target + 2] = (rgb[base + thirdChannel].toInt() and 0xff) * gain[2] + bias[2]
            }
        }
    }
}
