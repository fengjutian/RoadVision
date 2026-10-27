package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.inference.TensorLayout
import com.roadvision.roadsense.inference.TensorSpec

/**
 * Validates a model bundle before any ONNX session is created.
 *
 * Vehicle detection is mandatory; lane and plate+OCR are optional so the vehicle path can be
 * integrated first. Optional sections that are present are validated just as strictly as the
 * mandatory one. The decoders rely on specific output layouts (rows of six values, a single-channel
 * mask, or CTC logits including the blank class); catching a mismatch here turns a hard-to-diagnose
 * runtime failure into an actionable configuration error.
 */
object ModelBundleValidator {
    private const val ASSET_PREFIX = "models/"

    fun validate(config: PipelineModelConfig) {
        // Decode configs validate their own ranges in their init blocks.
        require(config.vehicleDecode.maxDetections in 1..1_000)
        require(config.vehicleLabels.all { (classId, label) -> classId >= 0 && label.isNotBlank() }) {
            "Vehicle labels must use non-negative class ids and non-blank names"
        }

        validateAsset("vehicle", config.vehicle)
        validateDetectionOutput("vehicle", config.vehicle)

        // A lane block without an asset is the weight-free classical detector: nothing to verify.
        config.lane?.asset?.let { lane ->
            validateAsset("lane", lane)
            validateLaneOutput("lane", lane)
        }

        config.plateOcr?.let { plateOcr ->
            require(plateOcr.plateDecode.maxDetections in 1..1_000)
            validateAsset("plate", plateOcr.plate)
            validateAsset("ocr", plateOcr.ocr)
            validateDetectionOutput("plate", plateOcr.plate)
            validateOcrOutput("ocr", plateOcr.ocr, plateOcr.alphabet.size)
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

    private fun validateDetectionOutput(role: String, asset: ModelAssetConfig) {
        val output = singleOutput(role, asset)
        require(output.shape.size == 3 && output.shape.last() == 6L) {
            "${role.replaceFirstChar { it.uppercase() }} output must be [1, N, 6], got ${output.shape.contentToString()}"
        }
    }

    private fun validateLaneOutput(role: String, asset: ModelAssetConfig) {
        val output = singleOutput(role, asset)
        require(output.shape.size == 4 && output.shape[1] == 1L) {
            "Lane mask must be [1, 1, H, W], got ${output.shape.contentToString()}"
        }
    }

    private fun validateOcrOutput(role: String, asset: ModelAssetConfig, alphabetSize: Int) {
        val output = singleOutput(role, asset)
        val classes = alphabetSize + 1L
        require(output.shape.size == 3 && output.shape.last() == classes) {
            "OCR logits must be [1, T, $classes] for $alphabetSize characters plus blank, " +
                "got ${output.shape.contentToString()}"
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
