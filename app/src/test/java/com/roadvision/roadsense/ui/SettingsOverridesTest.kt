package com.roadvision.roadsense.ui

import com.roadvision.roadsense.inference.ColorOrder
import com.roadvision.roadsense.inference.ExecutionProvider
import com.roadvision.roadsense.inference.ModelContract
import com.roadvision.roadsense.inference.TensorLayout
import com.roadvision.roadsense.inference.TensorSpec
import com.roadvision.roadsense.pipeline.ModelAssetConfig
import com.roadvision.roadsense.pipeline.PipelineModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsOverridesTest {
    @Test
    fun appliesProviderAndThresholdOverrides() {
        val result = SettingsOverrides.apply(
            base(),
            AppSettings(
                providerOverride = ExecutionProvider.NNAPI,
                vehicleConfidence = 0.7f,
                plateConfidence = 0.6f,
                laneThreshold = 0.8f,
            ),
        )

        assertEquals(ExecutionProvider.NNAPI, result.provider)
        assertEquals(0.7f, result.vehicleDecode.confidenceThreshold, 0.001f)
        assertEquals(0.6f, result.plateDecode.confidenceThreshold, 0.001f)
        assertEquals(0.8f, result.laneDecode.threshold, 0.001f)
    }

    @Test
    fun keepsManifestValuesWhenNoOverrideIsSet() {
        val base = base()
        val result = SettingsOverrides.apply(base, AppSettings())

        assertEquals(base.provider, result.provider)
        assertEquals(base.vehicleDecode, result.vehicleDecode)
        assertEquals(base.plateDecode, result.plateDecode)
        assertEquals(base.laneDecode, result.laneDecode)
    }

    private fun base() = PipelineModelConfig(
        vehicle = ModelAssetConfig("models/vehicle/model.onnx", contract(longArrayOf(1, 3, 64, 64), longArrayOf(1, -1, 6))),
        vehicleLabels = mapOf(0 to "car"),
        lane = ModelAssetConfig("models/lane/model.onnx", contract(longArrayOf(1, 3, 64, 64), longArrayOf(1, 1, 64, 64))),
        plate = ModelAssetConfig("models/plate/model.onnx", contract(longArrayOf(1, 3, 64, 64), longArrayOf(1, -1, 6))),
        ocr = ModelAssetConfig("models/ocr/model.onnx", contract(longArrayOf(1, 3, 48, 160), longArrayOf(1, -1, 3))),
        ocrAlphabet = listOf('A', 'B'),
        ocrBlankIndex = 2,
    )

    private fun contract(input: LongArray, output: LongArray) = ModelContract(
        id = "test",
        version = "1",
        sha256 = "a".repeat(64),
        input = TensorSpec("images", input),
        outputs = listOf(TensorSpec("output", output)),
        layout = TensorLayout.NCHW,
        colorOrder = ColorOrder.RGB,
    )
}
