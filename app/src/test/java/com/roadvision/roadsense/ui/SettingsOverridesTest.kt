package com.roadvision.roadsense.ui

import com.roadvision.roadsense.inference.ColorOrder
import com.roadvision.roadsense.inference.ExecutionProvider
import com.roadvision.roadsense.inference.ModelContract
import com.roadvision.roadsense.inference.TensorLayout
import com.roadvision.roadsense.inference.TensorSpec
import com.roadvision.roadsense.pipeline.LaneModelConfig
import com.roadvision.roadsense.pipeline.ModelAssetConfig
import com.roadvision.roadsense.pipeline.PipelineModelConfig
import com.roadvision.roadsense.pipeline.PlateOcrModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(0.6f, result.plateOcr!!.plateDecode.confidenceThreshold, 0.001f)
        assertEquals(0.8f, result.lane!!.decode.threshold, 0.001f)
    }

    @Test
    fun keepsManifestValuesWhenNoOverrideIsSet() {
        val base = base()
        val result = SettingsOverrides.apply(base, AppSettings())

        assertEquals(base.provider, result.provider)
        assertEquals(base.vehicleDecode, result.vehicleDecode)
        assertEquals(base.plateOcr, result.plateOcr)
        assertEquals(base.lane, result.lane)
    }

    @Test
    fun vehicleOnlyBundleStaysVehicleOnly() {
        val base = base(includeOptional = false)

        val result = SettingsOverrides.apply(
            base,
            AppSettings(plateConfidence = 0.6f, laneThreshold = 0.8f),
        )

        assertNull(result.lane)
        assertNull(result.plateOcr)
        assertEquals(base.vehicleDecode, result.vehicleDecode)
    }

    private fun base(includeOptional: Boolean = true) = PipelineModelConfig(
        vehicle = ModelAssetConfig("models/vehicle/model.onnx", contract(longArrayOf(1, 3, 64, 64), longArrayOf(1, -1, 6))),
        vehicleLabels = mapOf(0 to "car"),
        lane = if (includeOptional) {
            LaneModelConfig(
                ModelAssetConfig("models/lane/model.onnx", contract(longArrayOf(1, 3, 64, 64), longArrayOf(1, 1, 64, 64))),
            )
        } else {
            null
        },
        plateOcr = if (includeOptional) {
            PlateOcrModelConfig(
                plate = ModelAssetConfig(
                    "models/plate/model.onnx",
                    contract(longArrayOf(1, 3, 64, 64), longArrayOf(1, -1, 6)),
                ),
                ocr = ModelAssetConfig(
                    "models/ocr/model.onnx",
                    contract(longArrayOf(1, 3, 48, 160), longArrayOf(1, -1, 3)),
                ),
                alphabet = listOf('A', 'B'),
                blankIndex = 2,
            )
        } else {
            null
        },
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
