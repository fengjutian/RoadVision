package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.inference.ColorOrder
import com.roadvision.roadsense.inference.ModelContract
import com.roadvision.roadsense.inference.TensorLayout
import com.roadvision.roadsense.inference.TensorSpec
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBundleValidatorTest {
    @Test
    fun acceptsWellFormedNchwBundle() {
        ModelBundleValidator.validate(bundle())
    }

    @Test
    fun acceptsChannelLastBundle() {
        ModelBundleValidator.validate(bundle(layout = TensorLayout.NHWC))
    }

    @Test
    fun rejectsAssetOutsideModelsDirectory() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelBundleValidator.validate(bundle(vehiclePath = "assets/../secret.onnx"))
        }
        assertTrue(error.message.orEmpty().contains("asset path"))
    }

    @Test
    fun rejectsDynamicInputDimension() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelBundleValidator.validate(bundle(vehicleInput = longArrayOf(1, 3, -1, 640)))
        }
        assertTrue(error.message.orEmpty().contains("fixed and positive"))
    }

    @Test
    fun rejectsWrongInputChannelCount() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelBundleValidator.validate(bundle(vehicleInput = longArrayOf(1, 1, 640, 640)))
        }
        assertTrue(error.message.orEmpty().contains("3 channels"))
    }

    @Test
    fun rejectsVehicleOutputWithoutSixValues() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelBundleValidator.validate(bundle(vehicleOutput = longArrayOf(1, -1, 5)))
        }
        assertTrue(error.message.orEmpty().contains("Vehicle output"))
    }

    @Test
    fun rejectsLaneMaskWithMultipleChannels() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelBundleValidator.validate(bundle(laneOutput = longArrayOf(1, 2, 288, 800)))
        }
        assertTrue(error.message.orEmpty().contains("Lane mask"))
    }

    @Test
    fun rejectsOcrLogitsThatDoNotMatchAlphabet() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ModelBundleValidator.validate(bundle(ocrOutput = longArrayOf(1, -1, 4)))
        }
        assertTrue(error.message.orEmpty().contains("OCR logits"))
    }

    private fun bundle(
        vehiclePath: String = "models/vehicle/model.onnx",
        vehicleInput: LongArray? = null,
        vehicleOutput: LongArray = longArrayOf(1, -1, 6),
        laneOutput: LongArray = longArrayOf(1, 1, 288, 800),
        ocrOutput: LongArray = longArrayOf(1, -1, 3),
        layout: TensorLayout = TensorLayout.NCHW,
    ) = PipelineModelConfig(
        vehicle = ModelAssetConfig(vehiclePath, contract(vehicleInput ?: inputShape(layout), vehicleOutput, layout)),
        vehicleLabels = mapOf(0 to "car", 1 to "bus"),
        lane = ModelAssetConfig(
            "models/lane/model.onnx",
            contract(inputShape(layout), laneOutput, layout),
        ),
        plate = ModelAssetConfig(
            "models/plate/model.onnx",
            contract(inputShape(layout), longArrayOf(1, -1, 6), layout),
        ),
        ocr = ModelAssetConfig(
            "models/ocr/model.onnx",
            contract(inputShape(layout), ocrOutput, layout),
        ),
        ocrAlphabet = listOf('A', 'B'),
        ocrBlankIndex = 2,
    )

    private fun inputShape(layout: TensorLayout) = when (layout) {
        TensorLayout.NCHW -> longArrayOf(1, 3, 640, 640)
        TensorLayout.NHWC -> longArrayOf(1, 640, 640, 3)
    }

    private fun contract(input: LongArray, output: LongArray, layout: TensorLayout) = ModelContract(
        id = "test",
        version = "1",
        sha256 = "a".repeat(64),
        input = TensorSpec("images", input),
        outputs = listOf(TensorSpec("output", output)),
        layout = layout,
        colorOrder = ColorOrder.RGB,
    )
}
