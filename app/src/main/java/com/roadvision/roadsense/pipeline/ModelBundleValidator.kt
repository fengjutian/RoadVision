package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.inference.TensorLayout
import com.roadvision.roadsense.inference.TensorSpec

/**
 * Validates a model bundle before any ONNX session is created.
 *
 * The decoders rely on specific output layouts (rows of six values, a single-channel mask,
 * or CTC logits including the blank class). Catching a mismatch here turns a hard-to-diagnose
 * runtime failure into an actionable configuration error.
 */
object ModelBundleValidator {
    private const val ASSET_PREFIX = "models/"

    fun validate(config: PipelineModelConfig) {
        require(config.vehicleLabels.all { (classId, label) -> classId >= 0 && label.isNotBlank() }) {
            "Vehicle labels must use non-negative class ids and non-blank names"
        }

        validateAsset("vehicle", config.vehicle)
        validateAsset("lane", config.lane)
        validateAsset("plate", config.plate)
        validateAsset("ocr", config.ocr)

        val vehicleOutput = singleOutput("vehicle", config.vehicle)
        require(vehicleOutput.shape.size == 3 && vehicleOutput.shape.last() == 6L) {
            "Vehicle output must be [1, N, 6], got ${vehicleOutput.shape.contentToString()}"
        }

        val plateOutput = singleOutput("plate", config.plate)
        require(plateOutput.shape.size == 3 && plateOutput.shape.last() == 6L) {
            "Plate output must be [1, N, 6], got ${plateOutput.shape.contentToString()}"
        }

        val laneOutput = singleOutput("lane", config.lane)
        require(laneOutput.shape.size == 4 && laneOutput.shape[1] == 1L) {
            "Lane mask must be [1, 1, H, W], got ${laneOutput.shape.contentToString()}"
        }

        val ocrOutput = singleOutput("ocr", config.ocr)
        val classes = config.ocrAlphabet.size + 1L
        require(ocrOutput.shape.size == 3 && ocrOutput.shape.last() == classes) {
            "OCR logits must be [1, T, $classes] for ${config.ocrAlphabet.size} characters plus blank, " +
                "got ${ocrOutput.shape.contentToString()}"
        }
    }

    private fun validateAsset(role: String, asset: ModelAssetConfig) {
        val path = asset.assetPath
        require(path.startsWith(ASSET_PREFIX)) { "$role asset path must stay under $ASSET_PREFIX: $path" }
        require(!path.contains("..") && !path.contains('\\')) { "$role asset path is not a clean asset path: $path" }

        val input = asset.contract.input
        require(input.name.isNotBlank()) { "$role input tensor name is blank" }
        require(input.shape.size == 4) { "$role input must be rank 4, got ${input.shape.contentToString()}" }
        require(input.shape.all { it > 0 }) {
            "$role input dimensions must be fixed and positive, got ${input.shape.contentToString()}"
        }
        require(input.shape[0] == 1L) { "$role input batch must be 1, got ${input.shape[0]}" }

        val channelIndex = when (asset.contract.layout) {
            TensorLayout.NCHW -> 1
            TensorLayout.NHWC -> 3
        }
        require(input.shape[channelIndex] == 3L) {
            "$role ${asset.contract.layout} input must have 3 channels, got ${input.shape.contentToString()}"
        }
    }

    private fun singleOutput(role: String, asset: ModelAssetConfig): TensorSpec {
        require(asset.contract.outputs.size == 1) {
            "$role contract must declare exactly one output, got ${asset.contract.outputs.size}"
        }
        val output = asset.contract.outputs.single()
        require(output.name.isNotBlank()) { "$role output tensor name is blank" }
        require(output.shape.isNotEmpty()) { "$role output shape is empty" }
        require(output.shape.all { it == -1L || it > 0 }) {
            "$role output dimensions must be positive or dynamic (-1), got ${output.shape.contentToString()}"
        }
        return output
    }
}
