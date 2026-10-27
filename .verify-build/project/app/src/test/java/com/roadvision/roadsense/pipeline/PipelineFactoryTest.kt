package com.roadvision.roadsense.pipeline

import com.roadvision.roadsense.inference.ColorOrder
import com.roadvision.roadsense.inference.ExecutionProvider
import com.roadvision.roadsense.inference.ModelContract
import com.roadvision.roadsense.inference.TensorLayout
import com.roadvision.roadsense.inference.TensorSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineFactoryTest {
    @Test
    fun acceleratedProvidersFallBackToCpu() {
        assertEquals(
            listOf(ExecutionProvider.XNNPACK, ExecutionProvider.CPU),
            providerCandidates(ExecutionProvider.XNNPACK),
        )
        assertEquals(
            listOf(ExecutionProvider.NNAPI, ExecutionProvider.CPU),
            providerCandidates(ExecutionProvider.NNAPI),
        )
        assertEquals(listOf(ExecutionProvider.CPU), providerCandidates(ExecutionProvider.CPU))
    }

    @Test
    fun reportsOnlyModelsDeclaredInTheBundle() {
        assertEquals(listOf("vehicle"), enabledModels(config()))
        assertEquals(
            listOf("vehicle", "lane", "plate", "ocr"),
            enabledModels(config(includeLane = true, includePlateOcr = true)),
        )
    }

    private fun config(includeLane: Boolean = false, includePlateOcr: Boolean = false) = PipelineModelConfig(
        vehicle = ModelAssetConfig("models/vehicle/model.onnx", contract(longArrayOf(1, -1, 6))),
        vehicleLabels = mapOf(0 to "car"),
        lane = if (includeLane) {
            LaneModelConfig(ModelAssetConfig("models/lane/model.onnx", contract(longArrayOf(1, 1, 64, 64))))
        } else {
            null
        },
        plateOcr = if (includePlateOcr) {
            PlateOcrModelConfig(
                plate = ModelAssetConfig("models/plate/model.onnx", contract(longArrayOf(1, -1, 6))),
                ocr = ModelAssetConfig("models/ocr/model.onnx", contract(longArrayOf(1, -1, 3))),
                alphabet = listOf('A', 'B'),
                blankIndex = 2,
            )
        } else {
            null
        },
    )

    private fun contract(output: LongArray) = ModelContract(
        id = "test",
        version = "1",
        sha256 = "a".repeat(64),
        input = TensorSpec("images", longArrayOf(1, 3, 64, 64)),
        outputs = listOf(TensorSpec("output", output)),
        layout = TensorLayout.NCHW,
        colorOrder = ColorOrder.RGB,
    )
}
