package com.roadvision.roadsense.inference

import com.roadvision.roadsense.camera.ConvertedFrame

data class PreparedTensor(val values: FloatArray, val shape: LongArray, val transform: LetterboxTransform)

object TensorPreprocessor {
    fun prepare(frame: ConvertedFrame, contract: ModelContract): PreparedTensor {
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
        val transform = LetterboxTransform(frame.width, frame.height, inputWidth, inputHeight)
        val output = FloatArray(inputWidth * inputHeight * 3)
        val scale = minOf(inputWidth.toFloat() / frame.width, inputHeight.toFloat() / frame.height)
        val scaledWidth = frame.width * scale
        val scaledHeight = frame.height * scale
        val padX = (inputWidth - scaledWidth) / 2f
        val padY = (inputHeight - scaledHeight) / 2f

        for (y in 0 until inputHeight) for (x in 0 until inputWidth) {
            val sourceX = ((x - padX) / scale).toInt()
            val sourceY = ((y - padY) / scale).toInt()
            for (channel in 0..2) {
                val sourceChannel = when (contract.colorOrder) {
                    ColorOrder.RGB -> channel
                    ColorOrder.BGR -> 2 - channel
                }
                val raw = if (sourceX in 0 until frame.width && sourceY in 0 until frame.height) {
                    frame.rgb[(sourceY * frame.width + sourceX) * 3 + sourceChannel].toInt() and 255
                } else 0
                val normalized = (raw * contract.scale - contract.mean[channel]) / contract.std[channel]
                val target = when (contract.layout) {
                    TensorLayout.NCHW -> channel * inputWidth * inputHeight + y * inputWidth + x
                    TensorLayout.NHWC -> (y * inputWidth + x) * 3 + channel
                }
                output[target] = normalized
            }
        }
        return PreparedTensor(output, dimensions, transform)
    }
}
